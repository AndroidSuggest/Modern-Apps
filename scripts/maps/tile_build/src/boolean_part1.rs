impl Sweep {
    fn new() -> Sweep {
        Sweep { events: Vec::new(), queue: BinaryHeap::new() }
    }

    /// The sweep's total order over events, as a comparable key.
    ///
    /// One key shared by the queue and by the result sort, for two reasons. The algorithm wants
    /// both to agree — the ring walk steps between neighbouring result entries and relies on them
    /// being in the order the sweep produced. And a lexicographic key over scalars is a *total*
    /// order by construction, where a hand-written comparator is only as total as its worst case:
    /// a split can leave a segment short enough that its direction is float noise, and the standard
    /// library's sort detects the resulting inconsistency and panics.
    fn sort_key(&self, index: usize) -> (f64, f64, bool, f64, usize) {
        let e = &self.events[index];
        let o = self.events[e.other].p;
        // The direction the edge leaves this endpoint in. Two events at one point are ordered by
        // it, smallest first, which puts the lower edge into the status line first: a rightward
        // horizontal is angle 0, a vertical going up is pi/2, so the horizontal is processed first
        // and the vertical lands above it rather than below everything.
        let angle = (o.1 - e.p.1).atan2(o.0 - e.p.0);
        (e.p.0, e.p.1, !e.left, angle, index)
    }

    fn push_queue(&mut self, index: usize) {
        let key = self.sort_key(index);
        self.queue.push(QueueItem { index, key });
    }

    /// Add both endpoints of one edge.
    fn add_edge(&mut self, a: Pt, b: Pt, side: Side) {
        if pt_eq(a, b) {
            return; // A zero-length edge carries no boundary.
        }
        let ia = self.events.len();
        let ib = ia + 1;
        let a_is_left = match a.0.partial_cmp(&b.0) {
            Some(Ordering::Less) => true,
            Some(Ordering::Greater) => false,
            _ => a.1 < b.1,
        };
        self.events.push(Event {
            p: a,
            left: a_is_left,
            other: ib,
            side,
            edge_type: EdgeType::Normal,
            in_out: false,
            other_in_out: false,
            prev_in_result: None,
            in_result: false,
            pos: 0,
            forward: a_is_left,
        });
        self.events.push(Event {
            p: b,
            left: !a_is_left,
            other: ia,
            side,
            edge_type: EdgeType::Normal,
            in_out: false,
            other_in_out: false,
            prev_in_result: None,
            in_result: false,
            pos: 0,
            forward: a_is_left,
        });
        self.push_queue(ia);
        self.push_queue(ib);
    }

    fn add_polygons(&mut self, polys: &[Polygon], side: Side) {
        for poly in polys {
            for (at, ring) in poly.iter().enumerate() {
                if ring.len() < 3 {
                    continue;
                }
                // Tolerate both closed and open rings.
                let closed = pt_eq(ring[0], ring[ring.len() - 1]);
                let n = if closed { ring.len() - 1 } else { ring.len() };
                if n < 3 {
                    continue;
                }
                // Normalised so the interior is always to the left of the direction of travel:
                // counter-clockwise for an exterior, clockwise for a hole. The input makes no
                // promise about winding, and without this `forward` would mean nothing.
                let oriented = orient(ring[..n].to_vec(), at == 0);
                for i in 0..n {
                    self.add_edge(oriented[i], oriented[(i + 1) % n], side);
                }
            }
        }
    }

    /// Order two segments as the status line sees them: bottom to top at the sweep's x.
    fn segment_cmp(&self, a: usize, b: usize) -> Ordering {
        if a == b {
            return Ordering::Equal;
        }
        let (ea, eb) = (&self.events[a], &self.events[b]);
        let (oa, ob) = (self.events[ea.other].p, self.events[eb.other].p);
        if signed_area(ea.p, oa, eb.p) != 0.0 || signed_area(ea.p, oa, ob) != 0.0 {
            // Not collinear.
            if pt_eq(ea.p, eb.p) {
                return if ea.below(oa, ob) { Ordering::Less } else { Ordering::Greater };
            }
            // Whichever segment starts first is the one whose line the other is measured against.
            // Getting this pair the wrong way round is subtly wrong for sloped segments and badly
            // wrong for vertical ones, because `below` really answers "is the point LEFT of the
            // directed edge" and left of an upward vertical is west, not north.
            if event_cmp(&self.events, a, b) == Ordering::Less {
                return if ea.below(oa, eb.p) { Ordering::Less } else { Ordering::Greater };
            }
            return if eb.below(ob, ea.p) { Ordering::Greater } else { Ordering::Less };
        }
        // Collinear: keep subject below clip so the order is total and stable.
        if ea.side != eb.side {
            return if ea.side == Side::Subject { Ordering::Less } else { Ordering::Greater };
        }
        if pt_eq(ea.p, eb.p) {
            return a.cmp(&b);
        }
        event_cmp(&self.events, a, b)
    }

    /// Decide what an edge is inside, from the edge below it in the status line.
    fn compute_fields(&mut self, index: usize, below: Option<usize>) {
        match below {
            None => {
                // Nothing below: outside both polygons, so this edge enters its own.
                self.events[index].in_out = false;
                self.events[index].other_in_out = true;
            }
            Some(b) => {
                if self.events[index].side == self.events[b].side {
                    // Same polygon: the transition flips, the other polygon's state carries over.
                    self.events[index].in_out = !self.events[b].in_out;
                    self.events[index].other_in_out = self.events[b].other_in_out;
                } else {
                    // Crossing into the other polygon: the roles swap.
                    self.events[index].in_out = !self.events[b].other_in_out;
                    self.events[index].other_in_out = if self.is_vertical(b) {
                        !self.events[b].in_out
                    } else {
                        self.events[b].in_out
                    };
                }
                // Nearest surviving edge below, for nesting later.
                self.events[index].prev_in_result =
                    if !self.events[b].in_result || self.is_vertical(b) {
                        self.events[b].prev_in_result
                    } else {
                        Some(b)
                    };
            }
        }
    }

    fn in_result(&self, index: usize, op: Op) -> bool {
        let e = &self.events[index];
        match e.edge_type {
            EdgeType::Normal => match op {
                Op::Intersection => !e.other_in_out,
                Op::Union => e.other_in_out,
                Op::Difference => {
                    (e.side == Side::Subject && e.other_in_out)
                        || (e.side == Side::Clip && !e.other_in_out)
                }
                Op::Xor => true,
            },
            // Of two coincident edges only one may survive, and only for the ops where a shared
            // boundary is still a boundary of the answer.
            EdgeType::SameTransition => matches!(op, Op::Intersection | Op::Union),
            EdgeType::DifferentTransition => op == Op::Difference,
            EdgeType::NonContributing => false,
        }
    }

    /// Split an edge at `p`, so the arrangement has no partially overlapping segments.
    fn divide_segment(&mut self, index: usize, p: Pt) {
        let other = self.events[index].other;
        let side = self.events[index].side;
        // Both halves run the same way as the edge they came from.
        let forward = self.events[index].forward;

        let right_of_left = self.events.len(); // new right endpoint for the left half
        self.events.push(Event {
            p,
            left: false,
            other: index,
            side,
            edge_type: EdgeType::Normal,
            in_out: false,
            other_in_out: false,
            prev_in_result: None,
            in_result: false,
            pos: 0,
            forward,
        });
        let left_of_right = self.events.len(); // new left endpoint for the right half
        self.events.push(Event {
            p,
            left: true,
            other,
            side,
            edge_type: EdgeType::Normal,
            in_out: false,
            other_in_out: false,
            prev_in_result: None,
            in_result: false,
            pos: 0,
            forward,
        });

        self.events[index].other = right_of_left;
        self.events[other].other = left_of_right;

        self.push_queue(right_of_left);
        self.push_queue(left_of_right);
    }

    /// Test two status-line neighbours, splitting them where they meet.
    ///
    /// Returns true when the pair turned out to be collinear and overlapping, because the caller
    /// must then not treat them as an ordinary crossing.
    fn possible_intersection(&mut self, a: usize, b: usize) -> bool {
        let (a1, a2) = (self.events[a].p, self.events[self.events[a].other].p);
        let (b1, b2) = (self.events[b].p, self.events[self.events[b].other].p);

        let (count, ip1, _ip2) = intersect(a1, a2, b1, b2);
        if count == 0 {
            return false;
        }
        if count == 1 {
            // A single crossing. Split whichever segments do not already end there.
            if !pt_eq(a1, ip1) && !pt_eq(a2, ip1) {
                self.divide_segment(a, ip1);
            }
            if !pt_eq(b1, ip1) && !pt_eq(b2, ip1) {
                self.divide_segment(b, ip1);
            }
            return false;
        }

        // Collinear overlap — the case Greiner-Hormann cannot express, and the normal case at a
        // tile seam.
        if self.events[a].side == self.events[b].side {
            // Two edges of the *same* polygon lying on each other: degenerate input. Leave them;
            // the in/out bookkeeping cancels them out.
            return true;
        }

        // Only segments that coincide *exactly* can be marked, because marking is a statement about
        // one shared stretch. Anything else is first cut down until the overlapping middles do
        // coincide, and those get marked when the sweep next brings them together. Marking before
        // cutting labels the wrong fragment — `divide_segment` leaves `a` as the part to the left
        // of the cut, which is the part that does not overlap at all.
        let (ar, br) = (self.events[a].other, self.events[b].other);
        let left_shared = pt_eq(self.events[a].p, self.events[b].p);
        let right_shared = pt_eq(self.events[ar].p, self.events[br].p);

        if left_shared && right_shared {
            // Exactly the same stretch. One carries it, the other goes silent.
            let same = self.events[a].forward == self.events[b].forward;
            self.events[a].edge_type = EdgeType::NonContributing;
            self.events[b].edge_type =
                if same { EdgeType::SameTransition } else { EdgeType::DifferentTransition };
            return true;
        }

        if left_shared {
            // Same start, different lengths: mark the shared start-to-shorter-end stretch, then
            // cut the longer one there so the remainder is an ordinary segment.
            let a_first = self.sort_key(ar) < self.sort_key(br);
            let (shorter_end, longer) = if a_first { (ar, b) } else { (br, a) };
            let same = self.events[a].forward == self.events[b].forward;
            self.events[a].edge_type = EdgeType::NonContributing;
            self.events[b].edge_type =
                if same { EdgeType::SameTransition } else { EdgeType::DifferentTransition };
            let at = self.events[shorter_end].p;
            self.divide_segment(longer, at);
            return true;
        }

        if right_shared {
            // Same end: cut the one that starts earlier at the other's start.
            let a_first = self.sort_key(a) < self.sort_key(b);
            let (earlier, at) =
                if a_first { (a, self.events[b].p) } else { (b, self.events[a].p) };
            self.divide_segment(earlier, at);
            return true;
        }

        // No shared endpoint. Either one segment contains the other, or they stagger.
        let a_starts_first = self.sort_key(a) < self.sort_key(b);
        let (first, second) = if a_starts_first { (a, b) } else { (b, a) };
        let (first_end, second_end) = if a_starts_first { (ar, br) } else { (br, ar) };
        if self.sort_key(second_end) < self.sort_key(first_end) {
            // `first` swallows `second`: cut it at both of `second`'s ends. After the first cut,
            // the piece carrying the far end is reachable through that end's partner.
            let (lo, hi) = (self.events[second].p, self.events[second_end].p);
            self.divide_segment(first, lo);
            let right_piece = self.events[first_end].other;
            self.divide_segment(right_piece, hi);
        } else {
            // Staggered: cut each at the other's inner endpoint.
            let lo = self.events[second].p;
            let hi = self.events[first_end].p;
            self.divide_segment(first, lo);
            self.divide_segment(second, hi);
        }
        true
    }

    fn run(&mut self, op: Op) -> Vec<Polygon> {
        // The status line: indices of left events whose segments the sweep currently crosses,
        // ordered bottom to top. A sorted `Vec` rather than a balanced tree — insertion is O(n),
        // but n is the number of segments crossing one vertical line, which for tile-sized input is
        // small, and the constant factor of a flat array beats the pointer chasing.
        let mut status: Vec<usize> = Vec::new();
        let mut done: Vec<usize> = Vec::new();

        while let Some(item) = self.queue.pop() {
            let index = item.index;
            if self.events[index].left {
                let at = status.partition_point(|&s| self.segment_cmp(s, index) == Ordering::Less);
                status.insert(at, index);
                let below = if at > 0 { Some(status[at - 1]) } else { None };
                let above = status.get(at + 1).copied();
                self.compute_fields(index, below);

                if let Some(nx) = above {
                    if self.possible_intersection(index, nx) {
                        // Recompute: the overlap classification changed what these edges mean.
                        self.compute_fields(index, below);
                        let at2 = status.iter().position(|&s| s == nx);
                        if let Some(at2) = at2 {
                            let b2 = if at2 > 0 { Some(status[at2 - 1]) } else { None };
                            self.compute_fields(nx, b2);
                        }
                    }
                }
                if let Some(pv) = below {
                    if self.possible_intersection(pv, index) {
                        let at2 = status.iter().position(|&s| s == index);
                        if let Some(at2) = at2 {
                            let b2 = if at2 > 0 { Some(status[at2 - 1]) } else { None };
                            self.compute_fields(index, b2);
                        }
                    }
                }
            } else {
                // Right endpoint: its partner leaves the status line, and the neighbours it was
                // separating become adjacent and must be tested against each other.
                let left = self.events[index].other;
                if let Some(at) = status.iter().position(|&s| s == left) {
                    let below = if at > 0 { Some(status[at - 1]) } else { None };
                    let above = status.get(at + 1).copied();
                    done.push(left);
                    status.remove(at);
                    if let (Some(pv), Some(nx)) = (below, above) {
                        self.possible_intersection(pv, nx);
                    }
                } else {
                    // Its left event was already taken out of the status line by a split. The edge
                    // still contributes — dropping it here silently loses a side of the answer,
                    // which is how a square came back as a triangle.
                    done.push(left);
                }
            }
        }


        self.connect(done, op)
    }

    /// Chain surviving edges into rings, then nest rings into polygons.
    fn connect(&mut self, done: Vec<usize>, op: Op) -> Vec<Polygon> {
        // Survival is decided here rather than as each edge leaves the sweep. An edge's
        // `other_in_out` can still be rewritten by a later `possible_intersection` between its old
        // neighbours, so asking during the sweep reads a field that is not final yet.
        for &e in &done {
            self.events[e].in_result = self.in_result(e, op);
        }

        // Both endpoints of every surviving edge, in sweep order. A ring is then walked by
        // stepping to the far end of an edge and then to whichever other edge shares that point.
        let mut result: Vec<usize> = Vec::new();
        for &e in &done {
            if self.events[e].in_result {
                result.push(e);
                result.push(self.events[e].other);
            }
        }
        if result.is_empty() {
            return Vec::new();
        }
        result.sort_by(|&a, &b| {
            let (ka, kb) = (self.sort_key(a), self.sort_key(b));
            ka.0
                .total_cmp(&kb.0)
                .then_with(|| ka.1.total_cmp(&kb.1))
                // Right endpoints first at a shared point, as in the queue.
                .then_with(|| kb.2.cmp(&ka.2))
                .then_with(|| ka.3.total_cmp(&kb.3))
                .then_with(|| ka.4.cmp(&kb.4))
        });
        for (i, &e) in result.iter().enumerate() {
            self.events[e].pos = i;
        }

        let mut used = vec![false; result.len()];
        let mut rings: Vec<Vec<Pt>> = Vec::new();

        for start in 0..result.len() {
            if used[start] {
                continue;
            }
            let mut ring: Vec<Pt> = vec![self.events[result[start]].p];
            let mut pos = start;
            loop {
                used[pos] = true;
                // The far end of the edge at `pos`.
                pos = self.events[self.events[result[pos]].other].pos;
                used[pos] = true;
                ring.push(self.events[result[pos]].p);
                match self.next_unused(&result, &used, pos, start) {
                    Some(next) => pos = next,
                    None => break,
                }
            }
            // The walk returns to its start, so the closing duplicate is dropped.
            if ring.len() > 1 && pt_eq(ring[0], ring[ring.len() - 1]) {
                ring.pop();
            }
            if ring.len() >= 3 {
                rings.push(ring);
            }
        }

        assemble(rings)
    }

    /// The next unused entry sharing `pos`'s point, searched forward then back.
    ///
    /// Bounded below by `start` so a walk cannot wander into a ring that has already been closed.
    fn next_unused(
        &self,
        result: &[usize],
        used: &[bool],