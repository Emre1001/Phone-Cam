macro_rules! asset {
    ($($path:literal => $file:literal, $ctype:literal;)*) => {
        pub fn get(path: &str) -> Option<(&'static str, &'static [u8])> {
            match path {
                "/" => Some(("text/html; charset=utf-8", include_bytes!("../ui/index.html"))),
                $($path => Some(($ctype, include_bytes!(concat!("../ui/", $file)))),)*
                _ => None,
            }
        }
    };
}

asset! {
    "/index.html" => "index.html", "text/html; charset=utf-8";
    "/app.css" => "app.css", "text/css; charset=utf-8";
    "/app.js" => "app.js", "text/javascript; charset=utf-8";
    "/boot.js" => "boot.js", "text/javascript; charset=utf-8";
    "/motion.css" => "motion.css", "text/css; charset=utf-8";
    "/motion.js" => "motion.js", "text/javascript; charset=utf-8";
    "/i18n.js" => "i18n.js", "text/javascript; charset=utf-8";
    "/language-picker.css" => "language-picker.css", "text/css; charset=utf-8";
    "/language-picker.js" => "language-picker.js", "text/javascript; charset=utf-8";
    "/update-dialog.css" => "update-dialog.css", "text/css; charset=utf-8";
    "/update-dialog.js" => "update-dialog.js", "text/javascript; charset=utf-8";
    "/back-stack.js" => "back-stack.js", "text/javascript; charset=utf-8";
    "/locales/en.js" => "locales/en.js", "text/javascript; charset=utf-8";
    "/locales/de.js" => "locales/de.js", "text/javascript; charset=utf-8";
    "/locales/tr.js" => "locales/tr.js", "text/javascript; charset=utf-8";
    "/icon.svg" => "icon.svg", "image/svg+xml";
}
