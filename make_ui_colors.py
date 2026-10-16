with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "r") as f:
    content = f.read()

# Remove A_DIRECT from constant list if it didn't get placed correctly
content = content.replace(
    "final String A_APPEARANCE = \"#FF375F\", A_PRIVACY = \"#5E5CE6\", A_MEDIA = \"#FF9F0A\", A_TOOLS = \"#8E8E93\", A_DIRECT = \"#0A84FF\";",
    "final String A_APPEARANCE = \"#FF375F\", A_PRIVACY = \"#5E5CE6\", A_MEDIA = \"#FF9F0A\", A_TOOLS = \"#8E8E93\";"
)

content = content.replace(
    "private static final String A_TOOLS      = \"#8E8E93\";\n    private static final String A_DIRECT     = \"#0A84FF\";\n    private static final String A_DIRECT     = \"#0A84FF\";\n",
    "private static final String A_TOOLS      = \"#8E8E93\";\n"
)
content = content.replace(
    "private static final String A_TOOLS      = \"#8E8E93\";\n    private static final String A_DIRECT     = \"#0A84FF\";\n",
    "private static final String A_TOOLS      = \"#8E8E93\";\n"
)

# And now add it exactly where we know it needs to be inside the file
# Replace all uses of A_DIRECT with "#0A84FF" inline in the new loadHideChatsMenu
content = content.replace("A_DIRECT", "\"#0A84FF\"")

with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "w") as f:
    f.write(content)
