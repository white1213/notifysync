@echo off
rem 一次性清理：删除被未知句柄占用的旧构建目录（运行后可删除本文件）
rmdir /s /q "C:\Users\27730\.zcode\workspace\draft-888b421b-cfaf-4cff-83bb-31cd0d1b4475\notifysync\windows\dist" 2>nul
if exist "C:\Users\27730\.zcode\workspace\draft-888b421b-cfaf-4cff-83bb-31cd0d1b4475\notifysync\windows\dist" (
  echo 删除失败，句柄仍被占用；重启电脑后再运行本文件一次即可
) else (
  echo 已删除成功。本文件可以删了。
)
pause
