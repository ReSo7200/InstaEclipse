with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "r") as f:
    content = f.read()

content = content.replace(
    "private final Map<String, Boolean> stagedChanges = new HashMap<>();",
    "private final Map<String, Boolean> stagedChanges = new HashMap<>();\n    private final Map<String, Integer> stagedIntChanges = new HashMap<>();"
)

old_stage = """        if (stagedChanges.isEmpty()) {
            fabSave.hide();
        } else {
            fabSave.show();
        }
    }"""
new_stage = """        if (stagedChanges.isEmpty() && stagedIntChanges.isEmpty()) {
            fabSave.hide();
        } else {
            fabSave.show();
        }
    }

    private void stageChangeInt(String prefKey, int value) {
        if (localCache.getInt(prefKey, 0) == value) {
            stagedIntChanges.remove(prefKey);
        } else {
            stagedIntChanges.put(prefKey, value);
        }

        if (stagedChanges.isEmpty() && stagedIntChanges.isEmpty()) {
            fabSave.hide();
        } else {
            fabSave.show();
        }
    }"""
content = content.replace(old_stage, new_stage)

content = content.replace("if (stagedChanges.isEmpty()) return;", "if (stagedChanges.isEmpty() && stagedIntChanges.isEmpty()) return;")

old_commit = """            requireContext().sendBroadcast(intent);
        }
        editor.apply();
        stagedChanges.clear();"""
new_commit = """            requireContext().sendBroadcast(intent);
        }
        for (Map.Entry<String, Integer> entry : stagedIntChanges.entrySet()) {
            String key = entry.getKey();
            int value = entry.getValue();

            editor.putInt(key, value);

            Intent intent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
            intent.putExtra("key", key);
            intent.putExtra("value", value);
            requireContext().sendBroadcast(intent);
        }
        editor.apply();
        stagedChanges.clear();
        stagedIntChanges.clear();"""
content = content.replace(old_commit, new_commit)

with open("app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java", "w") as f:
    f.write(content)
