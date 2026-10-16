with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "r") as f:
    content = f.read()

content = content.replace(
    "final String A_APPEARANCE = \"#FF375F\", A_PRIVACY = \"#5E5CE6\", A_MEDIA = \"#FF9F0A\", A_TOOLS = \"#8E8E93\";",
    "final String A_APPEARANCE = \"#FF375F\", A_PRIVACY = \"#5E5CE6\", A_MEDIA = \"#FF9F0A\", A_TOOLS = \"#8E8E93\", A_DIRECT = \"#0A84FF\";"
)

with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "w") as f:
    f.write(content)
