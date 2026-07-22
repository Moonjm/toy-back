rootProject.name = "toy-back"

include(":common-core", ":common-auth", ":common-file")
project(":common-core").projectDir = file("common/core")
project(":common-auth").projectDir = file("common/auth")
project(":common-file").projectDir = file("common/file")

include(":daily-record", ":family-tree")
project(":daily-record").projectDir = file("apps/daily-record")
project(":family-tree").projectDir = file("apps/family-tree")
