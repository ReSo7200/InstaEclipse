with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "r") as f:
    lines = f.readlines()

new_lines = []
in_hide_chats = False
for i, line in enumerate(lines):
    if "final String A_APPEARANCE =" in line:
        new_lines.append(line.replace(";", ", A_DIRECT = \"#0A84FF\";\n"))
    elif "createNav(R.drawable.ic_eye_off, A_PRIVACY, getString(R.string.ig_hide_chats_title), this::loadHideChatsMenu)" in line:
        pass # remove
    elif "defs.add(getString(R.string.feat_group_privacy));" in line:
        new_lines.append("        defs.add(getString(R.string.feat_group_direct));\n")
        new_lines.append("        defs.add(Arrays.asList(\n")
        new_lines.append("                createNav(R.drawable.ic_eye_off, A_DIRECT, getString(R.string.ig_hide_chats_title), this::loadHideChatsMenu)\n")
        new_lines.append("        ));\n\n")
        new_lines.append(line)
    elif "private void loadHideChatsMenu() {" in line:
        in_hide_chats = True
        new_lines.append(line)
    elif in_hide_chats:
        if "defs.add(getString(R.string.feat_features));" in line:
            new_lines.append("        final String[] hideModeLabels = {\n")
            new_lines.append("                getString(R.string.ig_direct_hide_chats_mode_off),\n")
            new_lines.append("                getString(R.string.ig_direct_hide_chats_mode_eye),\n")
            new_lines.append("                getString(R.string.ig_direct_hide_chats_mode_back)\n")
            new_lines.append("        };\n")
        elif "createSwitch(R.drawable.ic_eye_off, \"#5E5CE6\", getString(R.string.ig_dialog_misc_hide_chats), \"hideSpecificChats\")" in line:
            new_lines.append("                createAction(R.drawable.ic_eye_off, A_DIRECT, getString(R.string.ig_direct_hide_chats_mode), v -> {\n")
            new_lines.append("                    new android.app.AlertDialog.Builder(requireContext())\n")
            new_lines.append("                        .setTitle(getString(R.string.ig_direct_hide_chats_mode))\n")
            new_lines.append("                        .setSingleChoiceItems(hideModeLabels, localCache.getInt(\"hideChatsMode\", 0), (dialog, which) -> {\n")
            new_lines.append("                            stageChangeInt(\"hideChatsMode\", which);\n")
            new_lines.append("                            stageChange(\"hideSpecificChats\", which != 0);\n")
            new_lines.append("                            dialog.dismiss();\n")
            new_lines.append("                            if (\"hidechats\".equals(currentMenu)) loadHideChatsMenu();\n")
            new_lines.append("                        })\n")
            new_lines.append("                        .show();\n")
            new_lines.append("                }, hideModeLabels[localCache.getInt(\"hideChatsMode\", 0)]),\n")
            new_lines.append("                createAction(R.drawable.ic_timer, A_DIRECT, getString(R.string.ig_direct_back_long_press_duration), v -> {\n")
            new_lines.append("                    String[] durs = new String[10];\n")
            new_lines.append("                    for (int j = 0; j < 10; j++) durs[j] = (j + 1) + \"s\";\n")
            new_lines.append("                    new android.app.AlertDialog.Builder(requireContext())\n")
            new_lines.append("                        .setTitle(getString(R.string.ig_direct_back_long_press_duration))\n")
            new_lines.append("                        .setSingleChoiceItems(durs, localCache.getInt(\"hideChatsBackLongPressSeconds\", 2) - 1, (dialog, which) -> {\n")
            new_lines.append("                            stageChangeInt(\"hideChatsBackLongPressSeconds\", which + 1);\n")
            new_lines.append("                            dialog.dismiss();\n")
            new_lines.append("                            if (\"hidechats\".equals(currentMenu)) loadHideChatsMenu();\n")
            new_lines.append("                        })\n")
            new_lines.append("                        .show();\n")
            new_lines.append("                }, localCache.getInt(\"hideChatsBackLongPressSeconds\", 2) + \"s\")\n")
            new_lines.append("        ));\n\n")
            new_lines.append("        defs.add(Arrays.asList(\n")
            new_lines.append("                createSwitch(R.drawable.ic_folder, A_DIRECT, getString(R.string.ig_direct_empty_general_folder), \"emptyGeneralFolder\"),\n")
            new_lines.append("                createSwitch(R.drawable.ic_eye_off, A_DIRECT, getString(R.string.ig_direct_hide_folders_bar), \"hideDirectFoldersBar\")\n")
        elif "showMenu(getString(R.string.ig_hide_chats_title), defs);" in line:
            in_hide_chats = False
            new_lines.append(line)
        else:
            new_lines.append(line)
    else:
        new_lines.append(line)

with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "w") as f:
    f.writelines(new_lines)
