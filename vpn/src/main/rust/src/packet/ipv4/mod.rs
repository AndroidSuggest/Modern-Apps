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

use bitfield_struct::bitfield;
use duplicate::duplicate_item;
use eyre::{bail, eyre};
use std::{fmt::Debug, net::Ipv4Addr};
use zerocopy::{FromBytes, Immutable, IntoBytes, KnownLayout, TryFromBytes, Unaligned, big_endian};

mod protocol;
pub use protocol::*;

use crate::packet::{DecodeError, Decoder, PseudoHeaderV4, Tcp, TcpDecoder, Udp, UdpDecoder};

use super::util::size_must_be;

/// An IPv4 packet.
///
/// This is a dynamically sized [`zerocopy`] type which allows for cheap conversions.
/// The generic payload allows you to compose packet types like `Ipv4<Udp<WgData>>`.
///
/// Use [`Ipv4Decoder`] and [`Ipv4PayloadDecoder`] for parsing into these packet types from
/// byte slices and such. You can also use [`FromBytes`] and [`IntoBytes`] if you want minimal
/// validation [Read more](crate::packet).
#[repr(C)]
#[derive(Debug, FromBytes, IntoBytes, KnownLayout, Unaligned, Immutable, PartialEq, Eq)]
pub struct Ipv4<Payload: ?Sized = [u8]> {
    /// IPv4 header.
    pub header: Ipv4Header,
    /// IPv4 payload.
    pub payload: Payload,
}

/// A [`Decoder`] from an [`Ipv4`] with [`Ipv4Options`] into an [`Ipv4`] without options.
pub struct Ipv4AssumeNoOptions;

/// An [`Ipv4`] [`Decoder`].
pub struct Ipv4Decoder {
    /// Fail if IP version field is not `4`.
    pub version: bool,
    /// Fail if IHL is invalid (<5 or too big).
    pub ihl: bool,
    /// Fail if IPv4 header checksum is incorrect.
    pub checksum: bool,
    /// Fail if IPv4 header length is invalid or too big.
    pub length: bool,
    /// Truncate the buffer if it's longer than header lengths.
    pub truncate: bool,
}

impl Ipv4Decoder {
    /// Validate as *much* as possible about the decoded packet.
    pub const CHECK_ALL: Self = Self {
        version: true,
        ihl: true,
        checksum: true,
        length: true,
        truncate: true,
    };

    /// Validate as *little* as possible about the decoded packet.
    pub const UNCHECKED: Self = Self {
        version: false,
        ihl: false,
        checksum: false,
        length: false,
        truncate: false,
    };
}

/// Decode a byte slice into an [`Ipv4`] packet.
///
/// Since [`Ipv4::header`] only represents the non-optional part of the header,
/// the [`Ipv4::payload`] field here will be an [`Ipv4Options`], but it may or may actually not
/// contain any options.
impl Decoder<[u8], Ipv4<Ipv4Options<[u8]>>> for Ipv4Decoder {
    fn validate(&self, bytes: &[u8]) -> Result<usize, DecodeError> {
        let ipv4: &Ipv4 = Ipv4::try_ref_from_bytes(bytes)?;

        let len = validate_ipv4(self, ipv4)?;

        if self.ihl {
            let ihl = usize::from(ipv4.header.ihl());
            if ihl < 5 || ihl * size_of::<u32>() > bytes.len() {
                return Err(DecodeError::InvalidValue("IHL"));
            }
        }

        Ok(len)
    }
}

/// Decode an `Ipv4<Ipv4Options>` into an `Ipv4<[u8]>`.
///
/// This will fail if the IPv4 header contains optional fields.
impl Decoder<Ipv4<Ipv4Options<[u8]>>, Ipv4<[u8]>> for Ipv4AssumeNoOptions {
    fn validate(&self, ipv4: &Ipv4<Ipv4Options<[u8]>>) -> Result<usize, DecodeError> {
        if ipv4.header.ihl() == 5 {
            Ok(ipv4.as_bytes().len())
        } else {
            Err(DecodeError::InvalidValue("IHL"))
        }
    }
}

/// Decode a byte slice into an [`Ipv4`] packet (without optional header fields).
///
/// *Note*: If you want to support IPv4 options,
/// use the [`Decoder`] implementation for `Ipv4<Ipv4Options>`.
impl Decoder<[u8], Ipv4<[u8]>> for Ipv4Decoder {
    fn validate(&self, bytes: &[u8]) -> Result<usize, DecodeError> {
        let ipv4: &Ipv4 = Ipv4::try_ref_from_bytes(bytes)?;

        let len = validate_ipv4(self, ipv4)?;

        if self.ihl {
            let ihl = usize::from(ipv4.header.ihl());
            if ihl != 5 {
                return Err(DecodeError::InvalidValue("IHL"));
            }
        }

        Ok(len)
    }
}

fn validate_ipv4(d: &Ipv4Decoder, ipv4: &Ipv4) -> Result<usize, DecodeError> {
    let buf_len = ipv4.as_bytes().len();

    if d.version && ipv4.header.version() != 4 {
        return Err(DecodeError::InvalidValue("version"));
    }

    if d.checksum {
        let expected_csum = ipv4.header.compute_checksum();
        if ipv4.header.header_checksum.get() != expected_csum {
            return Err(DecodeError::InvalidValue("checksum"));
        }
    }

    let total_len = usize::from(ipv4.header.total_len.get());
    if (d.length || d.truncate) && (total_len > buf_len || total_len < Ipv4Header::LEN) {
        return Err(DecodeError::InvalidValue("total_len"));
    }

    Ok(if d.truncate { total_len } else { buf_len })
}

/// A [`Decoder`] for [`Ipv4::payload`] into a transport protocol like [`Udp`].
pub struct Ipv4PayloadDecoder<Inner> {
    /// Assert that [`IpNextProtocol`] matches the payload.
    pub ip_next_protocol: bool,
    /// Assert that the IP packet is not a fragment.
    pub dont_fragment: bool,
    /// Decoder for the inner transport protocol
    pub inner: Inner,
}

#[duplicate_item(
    Inner;
    [UdpDecoder];
    [TcpDecoder];
)]
impl Ipv4PayloadDecoder<Inner> {
    /// Validate as *much* as possible about the decoded payload.
    pub const CHECK_ALL: Self = Self {
        ip_next_protocol: true,
        dont_fragment: true,
        inner: Inner::CHECK_ALL,
    };

    /// Validate as *little* as possible about the decoded payload.
    pub const UNCHECKED: Self = Self {
        ip_next_protocol: false,
        dont_fragment: false,
        inner: Inner::UNCHECKED,
    };
}

/// Decode the [`Ipv4::payload`] into [`Udp`].
impl Decoder<Ipv4<[u8]>, Ipv4<Udp>> for Ipv4PayloadDecoder<UdpDecoder> {
    fn validate(&self, ipv4: &Ipv4<[u8]>) -> Result<usize, DecodeError> {
        if self.ip_next_protocol && ipv4.header.next_protocol() != IpNextProtocol::Udp {
            return Err(DecodeError::InvalidValue("protocol"));
        }

        if self.dont_fragment
            && (ipv4.header.fragment_offset() != 0 || ipv4.header.more_fragments())
        {
            return Err(DecodeError::InvalidValue(
                "fragment_offset / more_fragments",
            ));
        }

        if self.inner.checksum {
            let udp = Udp::<[u8]>::try_ref_from_bytes(&ipv4.payload)?;

            // In IPV4, the UDP checksum field is optional; 0 indicates "no checksum"
            if udp.header.checksum.get() != 0 {
                let header = PseudoHeaderV4::from_udp(
                    ipv4.header.source_address,
                    ipv4.header.destination_address,
                    udp,
                );
                let expected_csum = crate::packet::util::checksum_udp_with_skip(header, udp);
                if expected_csum != udp.header.checksum.get() {
                    return Err(DecodeError::InvalidValue("UDP checksum"));
                }
            }
        }

        let len = self.inner.validate(&ipv4.payload)?;
        Ok(len + Ipv4Header::LEN)
    }
}

/// Decode the [`Ipv4::payload`] into [`Tcp`].
impl Decoder<Ipv4<[u8]>, Ipv4<Tcp>> for Ipv4PayloadDecoder<TcpDecoder> {
    fn validate(&self, ipv4: &Ipv4<[u8]>) -> Result<usize, DecodeError> {
        if self.ip_next_protocol && ipv4.header.next_protocol() != IpNextProtocol::Tcp {
            return Err(DecodeError::InvalidValue("protocol"));
        }

        if self.dont_fragment
            && (ipv4.header.fragment_offset() != 0 || ipv4.header.more_fragments())
        {
            return Err(DecodeError::InvalidValue(
                "fragment_offset / more_fragments",
            ));
        }

        if self.inner.checksum {
            let tcp = Tcp::<[u8]>::try_ref_from_bytes(&ipv4.payload)?;

            let header = PseudoHeaderV4::from_tcp(
                ipv4.header.source_address,
                ipv4.header.destination_address,
                tcp,
            );
            let expected_csum = crate::packet::util::checksum_tcp_with_skip(header, tcp);
            if expected_csum != tcp.header.checksum.get() {
                return Err(DecodeError::InvalidValue("TCP checksum"));
            }
        }

        let len = self.inner.validate(&ipv4.payload)?;
        Ok(len + Ipv4Header::LEN)
    }
}

/// IPv4 options and payload.
#[repr(C)]
#[derive(FromBytes, IntoBytes, KnownLayout, Unaligned, Immutable)]
pub struct Ipv4Options<T: ?Sized = [u8]> {
    _pd: std::marker::PhantomData<T>,
    options_and_payload: [u8],
}

/// A bitfield struct containing the IPv4 fields `version` and `ihl`.
#[bitfield(u8)]
#[derive(FromBytes, IntoBytes, KnownLayout, Unaligned, Immutable, PartialEq, Eq)]
pub struct Ipv4VersionIhl {
    /// IPv4 `ihl` field (Internet Header Length).
    ///
    /// This determines the length of the IPv4 header as the number of 32-bit (or 4-byte)
    /// blocks, including optional fields. The minimum value is `5`, which implies no
    /// optional fields.
    #[bits(4)]
    pub ihl: u8,

    /// IPv4 `version` field. This must be `4`.
    #[bits(4)]
    pub version: u8,
}

/// A bitfield struct containing the IPv4 fields `dscp` and `ecn`.
#[bitfield(u8)]
#[derive(FromBytes, IntoBytes, KnownLayout, Unaligned, Immutable, PartialEq, Eq)]
pub struct Ipv4DscpEcn {
    #[bits(2)]
    pub ecn: u8,
    #[bits(6)]
    pub dscp: u8,
}

/// A bitfield struct containing the IPv4 bitflags and the `fragment_offset` field.
#[bitfield(u16, order = Msb, repr = big_endian::U16, from = big_endian::U16::new, into = big_endian::U16::get)]
#[derive(FromBytes, IntoBytes, KnownLayout, Unaligned, Immutable, PartialEq, Eq)]
pub struct Ipv4FlagsFragmentOffset {
    _reserved: bool,
    /// IPv4 `dont_fragment` flag.
    pub dont_fragment: bool,
    /// IPv4 `more_fragments` flag.
    pub more_fragments: bool,
    /// IPv4 `fragment_offset` field.
    #[bits(13)]
    pub fragment_offset: u16,
}

/// An IPv4 header.
#[repr(C, packed)]
#[derive(Clone, Copy, FromBytes, IntoBytes, KnownLayout, Unaligned, Immutable, PartialEq, Eq)]
pub struct Ipv4Header {
    /// IPv4 `version`, and `ihl` fields.
    pub version_and_ihl: Ipv4VersionIhl,
    /// IPv4 `dscp`, and `ecn` fields.
    pub dscp_and_ecn: Ipv4DscpEcn,
    /// Length of the IPv4 packet, including headers.
    pub total_len: big_endian::U16,
    /// IPv4 `identification`. This is used for fragmentation.
    pub identification: big_endian::U16,
    /// IPv4 bitflags, and `fragment_offset` fields.
    pub flags_and_fragment_offset: Ipv4FlagsFragmentOffset,
    /// Maximum number of hops for the IPv4 packet.
    pub time_to_live: u8,
    /// Protocol of the IPv4 payload.
    pub protocol: IpNextProtocol,
    /// Checksum of the IPv4 header.
    pub header_checksum: big_endian::U16,
    /// IPv4 source address. Use [`Ipv4Header::source`].
    pub source_address: big_endian::U32,
    /// IPv4 destination address. Use [`Ipv4Header::destination`].
    pub destination_address: big_endian::U32,
}

impl Ipv4Header {
    /// Construct an IPv4 header with the reasonable defaults.
    ///
    /// `payload` field is used to set the `total_len` field.
    pub const fn new(
        source: Ipv4Addr,
        destination: Ipv4Addr,
        protocol: IpNextProtocol,
        payload: &[u8],
    ) -> Self {
        Self::new_for_length(source, destination, protocol, payload.len() as u16)
    }

    /// Construct an IPv4 header with the reasonable defaults.
    ///
    /// `payload_len` is used to set the `total_len` field.
    /// The checksum is initialized to `0`.
    pub const fn new_for_length(
        source: Ipv4Addr,
        destination: Ipv4Addr,
        protocol: IpNextProtocol,
        payload_len: u16,
    ) -> Self {
        let header_len = size_of::<Ipv4Header>() as u16;
        let total_len = header_len + payload_len;

        Self {
            protocol,

            version_and_ihl: Ipv4VersionIhl::new().with_version(4).with_ihl(5),
            dscp_and_ecn: Ipv4DscpEcn::new(),
            total_len: big_endian::U16::new(total_len),
            identification: big_endian::U16::ZERO,
            flags_and_fragment_offset: Ipv4FlagsFragmentOffset::new(),
            time_to_live: 64, // default TTL in linux
            source_address: big_endian::U32::from_bytes(source.octets()),
            destination_address: big_endian::U32::from_bytes(destination.octets()),

            // TODO:
            header_checksum: big_endian::U16::ZERO,
        }
    }
}

impl Ipv4Header {
    /// Length of an [`Ipv4Header`], in bytes.
    pub const LEN: usize = size_must_be::<Ipv4Header>(20);

    /// Get IP version. Must be `4` for a valid IPv4 header.
    pub const fn version(&self) -> u8 {
        self.version_and_ihl.version()
    }

    /// Get [`ihl`](Ipv4VersionIhl::ihl)
    pub const fn ihl(&self) -> u8 {
        self.version_and_ihl.ihl()
    }

    /// Get [`source_address`](Ipv4Header::source_address).
    pub const fn source(&self) -> Ipv4Addr {
        let bits = self.source_address.get();
        Ipv4Addr::from_bits(bits)
    }

    /// Get [`destination_address`](Ipv4Header::destination_address).
    pub const fn destination(&self) -> Ipv4Addr {
        let bits = self.destination_address.get();
        Ipv4Addr::from_bits(bits)
    }

    /// Get [`protocol`](Ipv4Header::protocol).
    pub const fn next_protocol(&self) -> IpNextProtocol {
        self.protocol
    }

    /// Get [`dscp`](Ipv4DscpEcn::dscp).
    pub const fn dscp(&self) -> u8 {
        self.dscp_and_ecn.dscp()
    }

    /// Get [`ecn`](Ipv4DscpEcn::ecn).
    pub const fn ecn(&self) -> u8 {
        self.dscp_and_ecn.ecn()
    }

    /// Get [`dont_fragment`](Ipv4FlagsFragmentOffset::dont_fragment).
    pub const fn dont_fragment(&self) -> bool {
        self.flags_and_fragment_offset.dont_fragment()
    }

    /// Get [`more_fragments`](Ipv4FlagsFragmentOffset::more_fragments).
    pub const fn more_fragments(&self) -> bool {
        self.flags_and_fragment_offset.more_fragments()
    }

    /// Get [`fragment_offset`](Ipv4FlagsFragmentOffset::fragment_offset).
    ///
    /// This is the offset of IP fragment payload relative to the start of payload of the original
    /// packet. Note that the value returned is in units of 8 bytes.
    pub const fn fragment_offset(&self) -> u16 {
        self.flags_and_fragment_offset.fragment_offset()
    }

    /// Compute expected header checksum.
    pub fn compute_checksum(&self) -> u16 {
        crate::packet::util::checksum_ipv4_with_skip(self.as_bytes())
    }
}

impl Ipv4 {
    /// Maximum possible length of an IPv4 packet.
    pub const MAX_LEN: usize = 65535;
}

include!("mod_part1.rs");