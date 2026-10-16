with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "r") as f:
    content = f.read()

content = content.replace("import io.github.libxposed.api.XposedInterface;\n", "")

with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "w") as f:
    f.write(content)
