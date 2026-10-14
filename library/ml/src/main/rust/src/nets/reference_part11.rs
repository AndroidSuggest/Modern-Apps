impl Reference {
    /// Max pooling, floored and unpadded — which is what all 33 uses are.
    fn max_pool(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    // The most negative fp16 rather than -inf, as `maxpool.comp` uses:
                    // every window here is fully inside the input, so it is never the
                    // answer.
                    let mut best = -65504.0f32;
                    for ky in 0..p.kh {
                        let iy = oy * p.stride_h + ky;
                        if iy >= p.in_h {
                            continue;
                        }
                        for kx in 0..p.kw {
                            let ix = ox * p.stride_w + kx;
                            if ix >= p.in_w {
                                continue;
                            }
                            best = best.max(self.load(p.in0, plane + iy * p.in_w + ix)?);
                        }
                    }
                    self.store(p.out, nchw(p, oc, oy, ox), best)?;
                }
            }
        }
        Ok(())
    }

    /// Instance normalisation over the spatial axes, per channel, with no affine.
    ///
    /// Mirrors `shaders/instancenorm.comp`: biased variance like every inference
    /// norm, epsilon inside the square root like `torch.nn.InstanceNorm2d`.
    /// `count` is the channel count, because one invocation handles a whole plane.
    fn instance_norm(&mut self, p: &Push) -> Result<(), String> {
        if p.in_c == 0 {
            return Err("an instance norm over zero channels".into());
        }
        let plane = p.in_h * p.in_w;
        if plane == 0 {
            return Err("an instance norm over an empty plane".into());
        }
        let epsilon = f32::from_bits(p.param1_bits);
        for c in 0..p.count {
            let base = c * plane;
            let mut total = 0.0;
            for i in 0..plane {
                total += self.load(p.in0, base + i)?;
            }
            let mean = total / plane as f32;

            let mut variance = 0.0;
            for i in 0..plane {
                let centred = self.load(p.in0, base + i)? - mean;
                variance += centred * centred;
            }
            let inverse = 1.0 / (variance / plane as f32 + epsilon).sqrt();

            for i in 0..plane {
                let centred = self.load(p.in0, base + i)? - mean;
                self.store(p.out, base + i, centred * inverse)?;
            }
        }
        Ok(())
    }
}
