# Modern Xposed API (libxposed API 102)

Source of truth: `io.github.libxposed:api:102.0.0` (Maven Central; sources jar read 2026-10-18).
Frameworks: **Vector 2.2+** (JingMatrix, formerly "JingMatrix LSPosed") implements API 102.
Older JingMatrix LSPosed **v1.11.0 (7209)** implements only API **100** (`BeforeHookCallback`),
not 101/102 (no `Chain`/`HookBuilder`): a 101/102 module does not load its modern entry there.

## Packaging

| What | Where |
|---|---|
| Java entry class list | `app/src/main/resources/META-INF/xposed/java_init.list` (replaces `assets/xposed_init`) |
| Module config | `META-INF/xposed/module.prop` (Java properties): `minApiVersion`, `targetApiVersion`, optional `staticScope` |
| Default scope | `META-INF/xposed/scope.list`, one package per line |
| Name / description | `android:label` / `android:description` of the app |
| Dependency | `compileOnly("io.github.libxposed:api:102.0.0")` |
| R8 | `-adaptresourcefilecontents META-INF/xposed/java_init.list` and keep `public <init>()` of `XposedModule` subclasses |

## Entry and lifecycle

```java
public class Entry extends io.github.libxposed.api.XposedModule {   // public no-arg constructor
    @Override public void onModuleLoaded(ModuleLoadedParam p) {}     // p.getProcessName(), p.isSystemServer()
    @Override public void onPackageLoaded(PackageLoadedParam p) {}   // API 29+: p.getPackageName(), getApplicationInfo(),
                                                                     //   isFirstPackage(), getDefaultClassLoader()
    @Override public void onPackageReady(PackageReadyParam p) {}     // p.getClassLoader() = the app's real class loader
    // API 102: onHotReloading / onHotReloaded (module swapped without killing the process)
}
```

- The framework calls `attachFramework(XposedInterface)` itself; do nothing before `onModuleLoaded`.
- **Modules are not hooked into their own app** any more.
- No `IXposedHookZygoteInit`: the module APK path is `getModuleApplicationInfo().sourceDir`.

## Hooks: interceptor chain (101+)

```java
HookHandle h = hook(method)                 // XposedInterface#hook(Executable) -> HookBuilder
        .setPriority(PRIORITY_DEFAULT)      // 50; higher runs first
        .setExceptionMode(ExceptionMode.DEFAULT)
        .intercept(chain -> {
            Object self = chain.getThisObject();
            List<Object> args = chain.getArgs();     // read-only view
            Object result = chain.proceed();         // or proceed(newArgs), proceedWith(thisObj[, args])
            return result;                           // returning without proceed() = replace
        });
h.unhook();
```

- `Chain`: `getExecutable()`, `getThisObject()`, `getArgs()`, `getArg(i)`, `proceed()`, `proceed(Object[])`,
  `proceedWith(Object)`, `proceedWith(Object, Object[])`.
- Throwing from `intercept` throws to the caller (DEFAULT mode logs hooker crashes; PROTECTIVE swallows them).
- `hookClassInitializer(Class)` hooks `<clinit>`.
- `deoptimize(Executable)`: stop ART inlining a method so its hook fires.
- `getInvoker(Method)` / `getInvoker(Constructor)`: call the original (`setType(Type.ORIGIN)`) or the
  chain (`Type.Chain.FULL`), `invokeSpecial` for super calls.

## Gone vs legacy

| Legacy | Modern replacement |
|---|---|
| `XC_MethodHook` before/after + `MethodHookParam` | one `Hooker.intercept(Chain)` (before = code before `proceed()`, after = after it) |
| `param.setResult()` in before | return without calling `proceed()` |
| `param.args[i] = x` | `proceed(newArgs)` |
| `param.setThrowable(t)` | `throw t` |
| `XposedBridge.invokeOriginalMethod` | `getInvoker(m).setType(Invoker.Type.ORIGIN).invoke(this, args)` |
| `XposedBridge.log` | `log(priority, tag, msg)` |
| `XposedHelpers` | not shipped (libxposed/helper library, or our own reflection) |
| `XSharedPreferences` (read the module app's prefs) | `getRemotePreferences(group)` (written by the module app through the libxposed **service** library) |
| `XModuleResources` | none: build `Resources` from `getModuleApplicationInfo().sourceDir` (AssetManager) |
| Resource hooks (`XResources`) | removed (not used by InstaEclipse) |
| `IXposedHookZygoteInit.initZygote` | `onModuleLoaded` + `getModuleApplicationInfo()` |
