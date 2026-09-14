impl<P: ?Sized> Ipv4<P>
where
    Self: IntoBytes + Immutable,
{
    /// Update [`Ipv4Header::total_len`] according to how big `self` is.
    ///
    /// # Errors
    /// Returns an error if `self` is larger than [`Ipv4::MAX_LEN`].
    pub fn try_update_ip_len(&mut self) -> eyre::Result<()> {
        self.header.total_len = self
            .as_bytes()
            .len()
            .try_into()
            .map_err(|_| eyre!("IPv4 packet was larger than {}", u16::MAX))?;
        Ok(())
    }
}

impl<P> Ipv4<Ipv4Options<P>>
where
    P: TryFromBytes + Immutable + KnownLayout + ?Sized,
{
    fn options_and_payload_bytes(&self) -> eyre::Result<(&[u8], &[u8])> {
        let header_len = usize::from(self.header.ihl()) * size_of::<u32>();

        let Some(options_len) = header_len.checked_sub(Ipv4Header::LEN) else {
            bail!("Invalid IHL");
        };

        self.payload
            .options_and_payload
            .split_at_checked(options_len)
            .ok_or(eyre!("IHL larger than header"))
    }

    fn payload_bytes(&self) -> eyre::Result<&[u8]> {
        Ok(self.options_and_payload_bytes()?.1)
    }

    /// Get the payload of this IPv4 packet.
    ///
    /// # Errors
    ///
    /// Returns [`Err`] if this packet has an invalid `IHL`, or if the payload bytes fails to be
    /// cast into `P`.
    pub fn payload(&self) -> eyre::Result<&P> {
        let bytes = self.payload_bytes()?;
        let payload = P::try_ref_from_bytes(bytes).map_err(|e| eyre!("{e}"))?;
        Ok(payload)
    }
}

impl Debug for Ipv4Header {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Ipv4Header")
            .field("version", &self.version())
            .field("ihl", &self.ihl())
            .field("dscp", &self.dscp())
            .field("ecn", &self.ecn())
            .field("total_len", &self.total_len.get())
            .field("identification", &self.identification.get())
            .field("dont_fragment", &self.dont_fragment())
            .field("more_fragments", &self.more_fragments())
            .field("fragment_offset", &self.fragment_offset())
            .field("time_to_live", &self.time_to_live)
            .field("protocol", &self.protocol)
            .field("header_checksum", &self.header_checksum.get())
            .field("source_address", &self.source())
            .field("destination_address", &self.destination())
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use zerocopy::{FromBytes, IntoBytes, big_endian};

    use super::{Ipv4, Ipv4Decoder, Ipv4Header, Ipv4PayloadDecoder};
    use crate::packet::{DecodeError, Decoder, IpNextProtocol, Udp, UdpDecoder, UdpHeader};
    use std::net::Ipv4Addr;

    const EXAMPLE_IPV4_ICMP: &[u8] = &[
        0x45, 0x83, 0x0, 0x54, 0xa3, 0x13, 0x40, 0x0, 0x40, 0x1, 0xc6, 0x26, 0xa, 0x8c, 0xc2, 0xdd,
        0x1, 0x2, 0x3, 0x4, 0x8, 0x0, 0x51, 0x13, 0x0, 0x2b, 0x0, 0x1, 0xb1, 0x5c, 0x87, 0x68, 0x0,
        0x0, 0x0, 0x0, 0xa8, 0x28, 0x7, 0x0, 0x0, 0x0, 0x0, 0x0, 0x10, 0x11, 0x12, 0x13, 0x14,
        0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23,
        0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32,
        0x33, 0x34, 0x35, 0x36, 0x37,
    ];

    const EXAMPLE_IPV4_UDP: Ipv4<Udp<[u8; 12]>> = Ipv4 {
        header: Ipv4Header {
            header_checksum: big_endian::U16::new(0x78c4),
            ..Ipv4Header::new_for_length(
                Ipv4Addr::new(1, 2, 3, 4),
                Ipv4Addr::new(255, 254, 253, 252),
                IpNextProtocol::Udp,
                (UdpHeader::LEN + 12) as u16,
            )
        },
        payload: Udp {
            header: UdpHeader::new(12345, 65421, (UdpHeader::LEN + 12) as u16, 0x6b0f),
            payload: *b"Hello there!",
        },
    };

    const EXAMPLE_IPV4_UDP_RAW: &[u8] = &[
        0x45, 0x0, 0x0, 0x28, 0x0, 0x0, 0x0, 0x0, 0x40, 0x11, 0x78, 0xc4, 0x1, 0x2, 0x3, 0x4, 0xff,
        0xfe, 0xfd, 0xfc, 0x30, 0x39, 0xff, 0x8d, 0x0, 0x14, 0x6b, 0x0f, 0x48, 0x65, 0x6c, 0x6c,
        0x6f, 0x20, 0x74, 0x68, 0x65, 0x72, 0x65, 0x21,
    ];

    /// Test that [`decode_ref`] can decode a valid IPv4/UDP packet.
    #[test]
    fn ipv4_decode_and_validate() {
        let ipv4: &Ipv4 = Ipv4Decoder::CHECK_ALL
            .decode_ref(EXAMPLE_IPV4_UDP_RAW)
            .expect("IPv4 packet is valid");
        let ipv4_udp: &Ipv4<Udp> = Ipv4PayloadDecoder::<UdpDecoder>::CHECK_ALL
            .decode_ref(ipv4)
            .expect("IPv4/UDP packet is valid");

        assert_eq!(ipv4_udp.as_bytes(), EXAMPLE_IPV4_UDP_RAW);
    }

    /// Test that [`decode_ref`] errors on a bad IPv4 checksum.
    #[test]
    fn ipv4_decode_invalid_checksum() {
        let mut ipv4 = EXAMPLE_IPV4_UDP;
        ipv4.header.header_checksum.set(1234);

        let _ipv4_with_bad_checksum: &Ipv4 = Ipv4Decoder::UNCHECKED
            .decode_ref(ipv4.as_bytes())
            .expect("Validation is disabled");

        let Err(DecodeError::InvalidValue("checksum")) =
            Decoder::<_, Ipv4<[u8]>>::decode_ref(&Ipv4Decoder::CHECK_ALL, ipv4.as_bytes())
        else {
            panic!("Must fail with checksum error");
        };
    }

    /// Test that the [`Ipv4`] type has the expected bytewise layout.
    #[test]
    fn ipv4_layout() {
        assert_eq!(EXAMPLE_IPV4_UDP.as_bytes(), EXAMPLE_IPV4_UDP_RAW);
    }

    #[test]
    fn ipv4_header_layout() {
        let packet = Ipv4::<[u8]>::ref_from_bytes(EXAMPLE_IPV4_ICMP).unwrap();
        let header = &packet.header;

        assert_eq!(header.version(), 4);
        assert_eq!(header.ihl(), 5);
        assert_eq!(header.dscp(), 32);
        assert_eq!(header.ecn(), 0x3);
        assert_eq!(header.total_len, 84);
        assert_eq!(header.identification, 41747);
        assert!(header.dont_fragment());
        assert!(!header.more_fragments());
        assert_eq!(header.fragment_offset(), 0);
        assert_eq!(header.time_to_live, 64);
        assert_eq!(header.protocol, IpNextProtocol::Icmp);
        assert_eq!(header.header_checksum, 0xc626);
        assert_eq!(header.source(), Ipv4Addr::new(10, 140, 194, 221));
        assert_eq!(header.destination(), Ipv4Addr::new(1, 2, 3, 4));

        assert_eq!(
            packet.payload.len() + Ipv4Header::LEN,
            usize::from(header.total_len)
        );
    }
}
