# Build MSStudio distributions

[Русская версия](MSS_BUILD_RU.md)

This guide builds the Linux and Windows distributions from the repository root.
The installer target uses Bazel to compile the product and assemble the distribution.
It is different from a development run.

## Requirements

- A complete MSStudio repository checkout, including its submodules.
- Internet access for the first build and for dependencies that are not cached.
- Enough free disk space for Bazel, build caches, temporary files, and the artifacts.
- On Linux: Bash, Git, `tar`, and `curl` or `wget`.
- For AppImage packaging: Linux, `curl`, and AppImage runtime support. FUSE may be required.
- On Windows: Git, Windows PowerShell, and a command prompt or PowerShell.

Run the commands from the repository root. The `bazel.cmd` wrapper downloads the pinned Bazelisk version when necessary.
The build downloads its required tools and JetBrains Runtime when they are not cached.

The build writes artifacts to `out/msstudio/artifacts/` by default. A later build can replace or remove previous output.
Copy any artifacts you want to keep before you start another build.

## Linux distribution

On Linux, build the x86-64 archive with the bundled runtime:

```bash
./msstudio-installers.cmd \
  -Dintellij.build.target.os=linux \
  -Dintellij.build.target.arch=x64 \
  -Dintellij.build.non.bundled.plugin.dirs.to.include=none
```

The archive is `out/msstudio/artifacts/MSStudio-<build>.tar.gz`. The current build number is `271.SNAPSHOT`.
The archive contains the `MSStudio/` directory. Extract it and run `MSStudio/bin/msstudio.sh`.

To build for ARM64, replace `x64` with `aarch64`. If you omit `intellij.build.target.arch`, the build targets all supported architectures.
The ARM64 archive has the `-aarch64.tar.gz` suffix.

### AppImage

First build the Linux archive for the same architecture as the packaging host. Then run:

```bash
./msstudio-appimage.sh out/msstudio/artifacts/MSStudio-271.SNAPSHOT.tar.gz
```

Replace the archive name if the build number or architecture differs. Always pass the archive path explicitly.
Without it, the script selects the newest matching archive, which might target another architecture.

The result is `out/MSStudio-<date>-<host-architecture>.AppImage`. The script uses the host architecture for the name and AppImage runtime.
It downloads `appimagetool` and may download the AppImage runtime. It replaces `out/appimage-work` on each run.

To update an installation managed by Gear Lever, replace the installed AppImage with the new file through Gear Lever or your usual workflow.
Restart MSStudio after the update.

## Windows distribution

The Windows target can run on Linux. It downloads a Windows runtime and uses a host-specific NSIS tool for the installer.
The Windows build and its installer have not yet been verified in this checkout. Test both on Windows before release.

On Linux, build the x86-64 ZIP and request the EXE installer:

```bash
./msstudio-installers.cmd \
  -Dintellij.build.target.os=windows \
  -Dintellij.build.target.arch=x64 \
  -Dintellij.build.non.bundled.plugin.dirs.to.include=none
```

On Windows, run the same target from PowerShell:

```powershell
.\msstudio-installers.cmd '-Dintellij.build.target.os=windows' '-Dintellij.build.target.arch=x64' '-Dintellij.build.non.bundled.plugin.dirs.to.include=none'
```

Look in `out/msstudio/artifacts/` for `MSStudio-<build>.win.zip` and `MSStudio-<build>.exe`.
The ZIP contains the runtime. Extract it and start the IDE from its `bin` directory.
The Windows installer still inherits some IntelliJ IDEA names and settings. Verify the installer screens and installation path before release.

To request ARM64 artifacts, replace `x64` with `aarch64`. Test them on Windows ARM64.
The `-aarch64` suffix distinguishes those artifacts.

If the NSIS installer step fails, you can build only the ZIP on Linux:

```bash
./msstudio-installers.cmd \
  -Dintellij.build.target.os=windows \
  -Dintellij.build.target.arch=x64 \
  -Dintellij.build.non.bundled.plugin.dirs.to.include=none \
  -Dintellij.build.skip.build.steps=windows_exe_installer
```

On Windows, append `'-Dintellij.build.skip.build.steps=windows_exe_installer'` to the PowerShell command instead.
Skipping this step does not fix the installer. Investigate the NSIS error before you ship an EXE.

## Build options and checks

- `intellij.build.non.bundled.plugin.dirs.to.include=none` skips publication of non-bundled plugins. It does not remove bundled plugins.
- The build separately downloads and bundles the HOCON plugin. It patches the plugin's `until-build` to `271.*`.
  This does not guarantee compatibility with the current IDE build. Verify the plugin at runtime.
- The installer target skips Windows signing. The resulting Windows binaries and installer are unsigned.
- Build completion does not prove that a distribution runs. Launch each artifact on its target OS and architecture.
  Check startup, the tool windows, the HOCON plugin, and the installer or archive.
- If a build fails, inspect its output and `out/msstudio/log/`. Check network access and free space before retrying.
- Do not run builds that share the same output directory at the same time.

These instructions do not cover macOS builds, release signing, or publishing. Those steps require separate validation.
