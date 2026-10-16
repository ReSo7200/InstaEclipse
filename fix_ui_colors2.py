with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "r") as f:
    content = f.read()

content = content.replace(
    "    private static final String A_TOOLS      = \"#8E8E93\";\n",
    "    private static final String A_TOOLS      = \"#8E8E93\";\n    private static final String A_DIRECT     = \"#0A84FF\";\n"
)

with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "w") as f:
    f.write(content)
