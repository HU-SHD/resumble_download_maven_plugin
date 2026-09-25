def downloadedFile = new File(basedir, "target/downloads/maven-core-3.9.9.jar")
assert downloadedFile.exists() : "文件未下载"
assert downloadedFile.length() > 0 : "文件为空"
println "集成测试通过：文件已成功下载，大小 " + downloadedFile.length() + " 字节"