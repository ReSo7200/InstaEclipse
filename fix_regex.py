with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "r") as f:
    content = f.read()

content = content.replace("s.matches(\"\\d{15,}\")", "s.matches(\"\\\\\\\\d{15,}\")")

with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "w") as f:
    f.write(content)
