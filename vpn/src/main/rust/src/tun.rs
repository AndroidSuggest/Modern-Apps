#[derive(Clone)]
pub struct MtuWatcher { mtu: u16, modifier: i16 }
impl MtuWatcher {
    pub const fn new(mtu: u16) -> Self { Self { mtu, modifier: 0 } }
    pub fn get(&mut self) -> u16 { self.mtu.saturating_add_signed(self.modifier) }
    pub fn increase(self, value: u16) -> Option<Self> {
        Some(Self { modifier: self.modifier.checked_add(i16::try_from(value).ok()?)?, ..self })
    }
    pub fn decrease(self, value: u16) -> Option<Self> {
        Some(Self { modifier: self.modifier.checked_sub(i16::try_from(value).ok()?)?, ..self })
    }
}
