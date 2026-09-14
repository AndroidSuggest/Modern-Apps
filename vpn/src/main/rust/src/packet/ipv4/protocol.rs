// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// This file incorporates work covered by the following copyright and
// permission notice:
//
//   Copyright (c) Mullvad VPN AB. All rights reserved.
//
// SPDX-License-Identifier: MPL-2.0
#![allow(clippy::doc_markdown)]

use std::fmt::Debug;

use zerocopy::{FromBytes, Immutable, IntoBytes, KnownLayout, Unaligned};

/// Type of the `protocol`/`next_header` fields of IPv4 and IPv6.
///
/// The value indicates what is stored in the IP packet.
#[repr(transparent)]
#[derive(Clone, Copy, PartialEq, Eq, Immutable, Unaligned, FromBytes, IntoBytes, KnownLayout)]
pub struct IpNextProtocol(u8);

include!("protocol_part1.rs");
include!("protocol_part2.rs");