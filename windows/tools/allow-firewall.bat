@echo off
rem NotifySync 防火墙放行（右键"以管理员身份运行"）
rem 若 Windows 弹窗时已允许过，则无需运行本脚本
netsh advfirewall firewall add rule name="NotifySync TCP" dir=in action=allow protocol=TCP localport=37312 >nul 2>&1
netsh advfirewall firewall add rule name="NotifySync UDP" dir=in action=allow protocol=UDP localport=37311 >nul 2>&1
if %errorlevel%==0 (
  echo 防火墙规则已添加：TCP 37312 / UDP 37311
) else (
  echo 添加失败，请右键本文件选择"以管理员身份运行"
)
pause
