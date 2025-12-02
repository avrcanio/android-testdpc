cd C:\Users\avrca\Projekti\testDPC.qubit.mdm\android-testdpc
$env:BAZEL_SH="C:\Program Files\Git\bin\bash.exe"
.\.bazelisk\bazel.exe shutdown
.\.bazelisk\bazel.exe build //:testdpc --verbose_failures
.\.bazelisk\bazel.exe build //:testdpc --define variant=lite