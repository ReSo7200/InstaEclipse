with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "r") as f:
    content = f.read()

import re

# Runnable doesn't take v argument
content = re.sub(r'createClickable\(R\.drawable\.ic_eye_off, "#0A84FF", getString\(R\.string\.ig_direct_hide_chats_mode\), v -> \{', r'createClickable(R.drawable.ic_eye_off, "#0A84FF", getString(R.string.ig_direct_hide_chats_mode), () -> {', content)
content = re.sub(r'createClickable\(R\.drawable\.ic_timer, "#0A84FF", getString\(R\.string\.ig_direct_back_long_press_duration\), v -> \{', r'createClickable(R.drawable.ic_timer, "#0A84FF", getString(R.string.ig_direct_back_long_press_duration), () -> {', content)

with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "w") as f:
    f.write(content)
