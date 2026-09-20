C:\Java\jdk-17\bin\java.exe -Xmx6g -jar docx-to-xml-service-1.0.0.jar --spring.config.location=file:.\application.yml

rem процессы  java из PowerShell
rem Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId,CommandLine