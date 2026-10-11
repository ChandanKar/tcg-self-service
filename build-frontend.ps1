# VM Self-Service Platform - Frontend bundle (E14-T02)
# The bundle is built by Gradle and packaged by processResources (build/generated/frontend);
# this wrapper only runs those tasks. ./gradlew bootRun / bootJar / test do it automatically.
& "$PSScriptRoot\gradlew.bat" buildFrontend patchIndexHtml processResources --console=plain
exit $LASTEXITCODE
