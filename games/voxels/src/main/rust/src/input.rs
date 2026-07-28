use std::sync::{Mutex, OnceLock};
#[derive(Debug, Clone, Copy, Default)]
pub struct InputState {
    pub move_forward: f32, pub move_right: f32,
    pub look_yaw_delta: f32, pub look_pitch_delta: f32,
    pub jump: bool, pub sneak: bool, pub sprint: bool, pub toggle_fly: bool,
}
static INPUT: OnceLock<Mutex<InputState>> = OnceLock::new();
fn mutex() -> &'static Mutex<InputState> { INPUT.get_or_init(|| Mutex::new(InputState::default())) }
pub fn set_move(x: f32, y: f32) { if let Ok(mut g)=mutex().lock() { g.move_right=x.clamp(-1.0,1.0); g.move_forward=y.clamp(-1.0,1.0); } }
pub fn add_look(dyaw: f32, dpitch: f32) { if let Ok(mut g)=mutex().lock() { g.look_yaw_delta+=dyaw; g.look_pitch_delta+=dpitch; } }
pub fn snapshot_and_clear_look() -> InputState {
    if let Ok(mut g)=mutex().lock() { let c=*g; g.look_yaw_delta=0.0; g.look_pitch_delta=0.0; g.toggle_fly=false; g.jump=false; return c; }
    InputState::default()
}
pub fn set_action(jump: bool, sneak: bool, toggle_fly: bool, sprint: bool) {
    if let Ok(mut g)=mutex().lock() { if jump { g.jump=true; } g.sneak=sneak; if toggle_fly { g.toggle_fly=true; } g.sprint=sprint; }
}
