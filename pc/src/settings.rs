use crate::state::State;
use crate::util::config_dir;

/// Allowed caps for the virtual camera picture (short side, 0 = as received).
pub const VCAM_MAX: [u16; 3] = [0, 1080, 720];

pub fn load(st: &mut State) {
    let Ok(text) = std::fs::read_to_string(config_dir().join("settings.txt")) else { return };
    for line in text.lines() {
        let Some((k, v)) = line.split_once('=') else { continue };
        match k {
            "name" if !v.is_empty() => st.name = v.chars().take(40).collect(),
            "pin" if v.len() == 6 && v.bytes().all(|b| b.is_ascii_digit()) => st.pin = v.into(),
            "rotation" => st.rotation = v.parse().ok().filter(|r| [0, 90, 180, 270].contains(r)).unwrap_or(0),
            "mirror" => st.mirror = v == "1",
            "vcam_max" => st.vcam_max = v.parse().ok().filter(|m| VCAM_MAX.contains(m)).unwrap_or(1080),
            _ => {}
        }
    }
}

pub fn save(st: &State) {
    let text = format!(
        "name={}\npin={}\nrotation={}\nmirror={}\nvcam_max={}\n",
        st.name.replace('\n', " "),
        st.pin,
        st.rotation,
        st.mirror as u8,
        st.vcam_max
    );
    let _ = std::fs::write(config_dir().join("settings.txt"), text);
}
