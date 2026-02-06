# xg-glass Kotlin app template (universal_glasses)

This is a template project used by xg-glass init.

## What you need to change

In most cases, developers only need to modify the business logic module’s ExampleAppEntry.kt (the sample entry; recommended to rename/replace it):

- `ug_app_logic/src/main/java/.../ExampleAppEntry.kt`

## How to build / install / run

From your project root (the directory that contains `xg-glass.yaml`):

- `xg-glass build`
- `xg-glass install`
- `xg-glass run`

## Notes

The `settings.gradle.kts` / `app/build.gradle.kts` / `AndroidManifest.xml` in this template contain placeholders:

- `__XG_SDK_PATH__`
- `__XG_ENTRY_CLASS__`

These will be replaced with actual values by `xg-glass init`.

### Rokid note (CXR-M v1.0.4)

If you are connecting to **Rokid** glasses, CXR-M **v1.0.4** requires an SN authorization file (`.lc`) and your developer `clientSecret`.

- Put the SN authorization file into: `app/src/main/res/raw/`
  - Example: `app/src/main/res/raw/sn_0a9813....lc` (resource name is `sn_0a9813....`, **without** extension)
- Add the following to your project root `local.properties` (**do not commit it**):

```properties
rokid.clientSecret=xxxxxxxxxxxxxxxx
rokid.snRawName=sn_0a9813....
```

Alternatively, you can set environment variables: `ROKID_CLIENT_SECRET` and `ROKID_SN_RAW_NAME`.
