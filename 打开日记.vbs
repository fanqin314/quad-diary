Option Explicit
' QUAD DIARY - double-click launcher.
' Starts a hidden local server on 127.0.0.1:8080, then opens the browser.
' Keep this file pure ASCII: Windows parses .vbs with the system code page,
' so any Chinese text here would garble and break the script.

Dim sh, fso, base, nodeOk
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = base

If Not fso.FileExists(base & "\index.html") Then
  MsgBox "index.html is missing next to this launcher.", 16, "QUAD DIARY"
  WScript.Quit
End If

If Not fso.FileExists(base & "\server.js") Then
  MsgBox "server.js is missing next to this launcher.", 16, "QUAD DIARY"
  WScript.Quit
End If

nodeOk = (sh.Run("cmd /c where node >nul 2>nul", 0, True) = 0)
If Not nodeOk Then
  MsgBox "Node.js not found." & vbCrLf & vbCrLf & _
         "The local server needs Node.js so that the app can register a" & vbCrLf & _
         "service worker and be installed to the home screen." & vbCrLf & _
         "Install it from https://nodejs.org and double-click again.", 16, "QUAD DIARY"
  WScript.Quit
End If

' If the port is already taken, server.js exits on its own and the existing
' instance keeps serving - the browser step below still works.
sh.Run "node server.js", 0, False
WScript.Sleep 900
sh.Run "http://127.0.0.1:8080/index.html", 1, False
