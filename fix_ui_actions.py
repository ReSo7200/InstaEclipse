with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "r") as f:
    content = f.read()

# Replace only the ones that were wrongly added as createAction
content = content.replace("createAction(R.drawable.ic_eye_off, \"#0A84FF\", getString(R.string.ig_direct_hide_chats_mode)", "createClickable(R.drawable.ic_eye_off, \"#0A84FF\", getString(R.string.ig_direct_hide_chats_mode)")
content = content.replace("createAction(R.drawable.ic_timer, \"#0A84FF\", getString(R.string.ig_direct_back_long_press_duration)", "createClickable(R.drawable.ic_timer, \"#0A84FF\", getString(R.string.ig_direct_back_long_press_duration)")

with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "w") as f:
    f.write(content)
