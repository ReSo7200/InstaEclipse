with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "r") as f:
    content = f.read()

import re

# Need to replace `, hideModeLabels[localCache.getInt("hideChatsMode", 0)])` with just `)`
content = re.sub(r'\}, hideModeLabels\[localCache\.getInt\("hideChatsMode", 0\)\]\),', r'}),', content)

# Same for timer
content = re.sub(r'\}, localCache\.getInt\("hideChatsBackLongPressSeconds", 2\) \+ "s"\)', r'})', content)

with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "w") as f:
    f.write(content)
