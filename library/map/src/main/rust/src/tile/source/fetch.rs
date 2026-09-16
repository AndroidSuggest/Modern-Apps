#[cfg(target_os = "android")]
use tilecodec::proto::err;
use tilecodec::proto::Result;

/// What a range fetch returned.
pub struct RangeResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

/// Fetches a byte range. Separated from the policy so the policy is testable without a
/// JVM.
pub trait RangeFetcher {
    fn fetch(&self, url: &str, range: &str) -> Result<RangeResponse>;
}

/// A [`RangeFetcher`] over `library/jni-http`, and so over `:library:network`.
#[cfg(target_os = "android")]
pub struct JniRangeFetcher;

#[cfg(target_os = "android")]
impl RangeFetcher for JniRangeFetcher {
    fn fetch(&self, url: &str, range: &str) -> Result<RangeResponse> {
        // `Header` is an owned `(String, String)` pair, and `body` is `Option`, not a
        // slice: a GET has none.
        let headers = [("Range".to_string(), range.to_string())];
        match jni_http::request(jni_http::Method::Get, url, &headers, None) {
            Ok(response) => {
                // status 0 means the request never completed — a transport error the
                // bridge reports in-band rather than as an Err.
                if response.status == 0 {
                    let detail = response
                        .error()
                        .unwrap_or_else(|| "transport failure".into());
                    return err(format!(
                        "range request for {range} of {url} failed: {detail}"
                    ));
                }
                Ok(RangeResponse {
                    status: response.status,
                    body: response.body,
                })
            }
            Err(e) => err(format!("range request for {range} of {url} failed: {e:?}")),
        }
    }
}
