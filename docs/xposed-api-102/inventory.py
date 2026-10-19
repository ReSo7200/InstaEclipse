"""Writes docs/xposed-api-102/LEGACY_API.md: every legacy Xposed API use, per file."""
import os, re, sys, collections

root = sys.argv[1]
src = os.path.join(root, 'app/src/main/java')
out = os.path.join(root, 'docs/xposed-api-102/LEGACY_API.md')

PATTERNS = collections.OrderedDict([
    ('XposedBridge.hookMethod', r'XposedBridge\.hookMethod\b'),
    ('XposedBridge.hookAllMethods', r'XposedBridge\.hookAllMethods\b'),
    ('XposedBridge.hookAllConstructors', r'XposedBridge\.hookAllConstructors\b'),
    ('XposedBridge.invokeOriginalMethod', r'XposedBridge\.invokeOriginalMethod\b'),
    ('XposedBridge.log', r'XposedBridge\.log\b'),
    ('XposedHelpers.findAndHookMethod', r'XposedHelpers\.findAndHookMethod\b|(?<![\w.])findAndHookMethod\('),
    ('XposedHelpers.findAndHookConstructor', r'XposedHelpers\.findAndHookConstructor\b'),
    ('XposedHelpers.findClass', r'XposedHelpers\.findClass\b'),
    ('XposedHelpers.findClassIfExists', r'XposedHelpers\.findClassIfExists\b'),
    ('XposedHelpers.getObjectField', r'XposedHelpers\.getObjectField\b'),
    ('XposedHelpers.setObjectField', r'XposedHelpers\.setObjectField\b'),
    ('XposedHelpers.getBooleanField', r'XposedHelpers\.getBooleanField\b'),
    ('XposedHelpers.getIntField', r'XposedHelpers\.getIntField\b'),
    ('XposedHelpers.getStaticObjectField', r'XposedHelpers\.getStaticObjectField\b'),
    ('XposedHelpers.setStaticObjectField', r'XposedHelpers\.setStaticObjectField\b'),
    ('XposedHelpers.callMethod', r'XposedHelpers\.callMethod\b'),
    ('XposedHelpers.callStaticMethod', r'XposedHelpers\.callStaticMethod\b'),
    ('XposedHelpers.newInstance', r'XposedHelpers\.newInstance\b'),
    ('XposedHelpers.get/setAdditionalInstanceField', r'XposedHelpers\.(get|set)AdditionalInstanceField\b'),
    ('XposedHelpers.findMethodExact', r'XposedHelpers\.findMethodExact\b'),
    ('new XC_MethodHook', r'new XC_MethodHook\b'),
    ('new XC_MethodReplacement', r'new XC_MethodReplacement\b'),
    ('XC_MethodReplacement.returnConstant/DO_NOTHING', r'XC_MethodReplacement\.(returnConstant|DO_NOTHING)\b'),
    ('param.getThrowable/setThrowable', r'\.(getThrowable|setThrowable|hasThrowable|getResultOrThrowable)\('),
    ('XC_MethodHook.Unhook', r'\bUnhook\b'),
    ('XSharedPreferences', r'\bXSharedPreferences\b'),
    ('IXposedHookLoadPackage / LoadPackageParam', r'IXposedHookLoadPackage|LoadPackageParam'),
    ('IXposedHookZygoteInit / StartupParam', r'IXposedHookZygoteInit|StartupParam'),
    ('XModuleResources (module icons/strings)', r'XModuleResources'),
    ('AndroidAppHelper.currentApplication', r'AndroidAppHelper\.currentApplication'),
])

totals = collections.Counter()
per_file = {}
for dirpath, _, files in os.walk(src):
    for f in files:
        if not f.endswith('.java'):
            continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding='utf-8').read()
        if not re.search(r'de\.robv\.android\.xposed|XModuleResources|android\.app\.AndroidAppHelper', text):
            continue
        rel = os.path.relpath(p, src).replace('\\', '/')
        counts = collections.Counter()
        for name, pat in PATTERNS.items():
            n = len(re.findall(pat, text))
            if n:
                counts[name] = n
                totals[name] += n
        imports = sorted(set(re.findall(r'import (de\.robv\.android\.xposed[\w.]*);', text)))
        per_file[rel] = (counts, imports)

lines = ['# Legacy Xposed API in use (XposedBridge API 82)', '',
         'Snapshot of the code BEFORE the migration (branch point 6f70d5a). Regenerate from the current code with `python docs/xposed-api-102/inventory.py .`.',
         'Everything here must keep working through the compat layer (see MODERN_API.md).', '',
         '## Totals', '', '| API | Uses |', '|---|---|']
for name in PATTERNS:
    if totals[name]:
        lines.append('| `%s` | %d |' % (name, totals[name]))
lines += ['', '**%d files** import `de.robv.android.xposed`.' % len(per_file), '',
          '## Entry and packaging (legacy)', '',
          '- `app/src/main/assets/xposed_init` -> `ps.reso.instaeclipse.Xposed.Module`',
          '- `app/libs/api-82.jar` (compileOnly)',
          '- Settings on cold start: the hooked app reads the companion app prefs through `XSharedPreferences`',
          '  (`CompanionSync`, `FeedVideoDownloadHook` download folder); live changes come by broadcast.', '',
          '## Per file', '', '| File | Uses |', '|---|---|']
for rel in sorted(per_file):
    counts, imports = per_file[rel]
    uses = ', '.join('%s x%d' % (k, v) for k, v in counts.items()) or 'imports only: ' + ', '.join(i.split('.')[-1] for i in imports)
    lines.append('| `%s` | %s |' % (rel, uses))
os.makedirs(os.path.dirname(out), exist_ok=True)
open(out, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
print(len(per_file), 'files;', sum(totals.values()), 'uses')
