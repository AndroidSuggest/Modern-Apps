use tilecodec::proto::Result;
use tilecodec::stream::RangeReader;

/// A local file as a [`RangeReader`], for `file://` archives pushed to the device.
///
/// Each worker opens its **own** handle, so `read(offset, length)` is `read_at` and needs no
/// seek lock. Placed here (beside [`crate::tile::source::CachingRangeReader`]) so the bridge can choose between this
/// and the network path without the codec crate depending on Android.
///
/// Short reads are returned short — the same contract `CachingRangeReader` and the `RangeReader`
/// trait use — and `exact` in `mamaps::read` turns a short body into a parse error.
pub struct FileRangeReader {
    file: std::fs::File,
}

impl FileRangeReader {
    pub fn open(path: &std::path::Path) -> Result<Self> {
        let file = std::fs::File::open(path)
            .map_err(|e| tilecodec::proto::Error(format!("cannot open local archive {}: {e}", path.display())))?;
        Ok(FileRangeReader { file })
    }
}

impl RangeReader for FileRangeReader {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        if length == 0 {
            return Ok(Vec::new());
        }
        self.read_at(offset, length)
    }
}

#[cfg(target_os = "android")]
impl FileRangeReader {
    fn read_at(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        use std::os::unix::fs::FileExt;
        let mut buf = vec![0u8; length as usize];
        let mut got = 0usize;
        while got < buf.len() {
            let n = self.file.read_at(&mut buf[got..], offset + got as u64).map_err(|e| {
                tilecodec::proto::Error(format!("local archive read at {offset}+{got} failed: {e}"))
            })?;
            if n == 0 {
                break;
            }
            got += n;
        }
        buf.truncate(got);
        Ok(buf)
    }
}

#[cfg(not(target_os = "android"))]
impl FileRangeReader {
    fn read_at(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        use std::io::{Read, Seek, SeekFrom};
        let mut file = &self.file;
        file.seek(SeekFrom::Start(offset))
            .map_err(|e| tilecodec::proto::Error(format!("local archive seek at {offset} failed: {e}")))?;
        let mut buf = vec![0u8; length as usize];
        let mut got = 0usize;
        while got < buf.len() {
            let n = file.read(&mut buf[got..])
                .map_err(|e| tilecodec::proto::Error(format!("local archive read at {offset}+{got} failed: {e}")))?;
            if n == 0 {
                break;
            }
            got += n;
        }
        buf.truncate(got);
        Ok(buf)
    }
}
