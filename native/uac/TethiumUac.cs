// Small Windows-only bridge for human-operated UAC dialogs. .NET Framework 4.x; no external packages.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Automation;

internal sealed class TethiumUac : ServiceBase {
    const string ServiceId = "TethiumUac";
    static readonly string Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Tethium", "Uac");
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 262144 };
    static string Encode(object value) { lock(Json) return Json.Serialize(value); }
    static Dictionary<string,object> Decode(string value) { lock(Json) return Json.Deserialize<Dictionary<string,object>>(value); }
    static string Text(Dictionary<string,object> value, string key) { object v; return value.TryGetValue(key,out v) ? Convert.ToString(v) : ""; }
    static Dictionary<string,object> Obj(params object[] values) { var o=new Dictionary<string,object>();for(int i=0;i<values.Length;i+=2)o[(string)values[i]]=values[i+1];return o; }
    static string RandomId() { return Guid.NewGuid().ToString("N"); }
    static readonly object LogLock=new object();
    static void Log(string value) { try { lock(LogLock){string file=Path.Combine(Home,"service.log");if(File.Exists(file)&&new FileInfo(file).Length>1048576)File.WriteAllText(file,"");File.AppendAllText(file,DateTime.UtcNow.ToString("o")+" "+value+Environment.NewLine);} } catch {} }

    [MTAThread] static int Main(string[] args) {
        try {
            if(args.Length==2 && args[0]=="--worker") { Worker(args[1]);return 0; }
            if(args.Length==1 && args[0]=="--self-test") { SelfTest();return 0; }
            ServiceBase.Run(new TethiumUac { ServiceName=ServiceId, CanStop=true, AutoLog=false });return 0;
        } catch(Exception e) { Log(e.ToString());return 1; }
    }

    volatile bool stopped;
    HttpListener http;
    Process child;
    string pipe, owner, secret;
    int session=-1;
    readonly object gate=new object();
    volatile string snapshot=Encode(Obj("available",true,"pending",false));
    protected override void OnStart(string[] args) {
        if(!WindowsIdentity.GetCurrent().IsSystem)throw new InvalidOperationException("SYSTEM required");
        Native.EnablePrivileges();
        owner=Text(Decode(File.ReadAllText(Path.Combine(Home,"config.json"))),"ownerSid");
        if(!owner.StartsWith("S-1-5-21-",StringComparison.Ordinal))throw new InvalidOperationException("Invalid owner");
        secret=RandomId()+RandomId();
        // Reserve an ephemeral loopback listener. Descriptor is protected by the installation directory ACL.
        for(int attempt=0;attempt<10;attempt++) {
            var reservation=new System.Net.Sockets.TcpListener(IPAddress.Loopback,0);reservation.Start();int port=((IPEndPoint)reservation.LocalEndpoint).Port;reservation.Stop();
            try { http=new HttpListener();http.Prefixes.Add("http://127.0.0.1:"+port+"/");http.Start();
                File.WriteAllText(Path.Combine(Home,"bridge.json"),Encode(Obj("protocol",1,"port",port,"token",secret,"pid",Process.GetCurrentProcess().Id)),new UTF8Encoding(false));break;
            }catch(HttpListenerException){if(http!=null)http.Close();http=null;}
        }
        if(http==null)throw new IOException("Cannot start loopback bridge");
        Task.Run((Action)Listen);Task.Run((Action)Monitor);Log("Started");
    }
    protected override void OnStop() {
        stopped=true;if(http!=null)http.Close();lock(gate)StopChild();
        try { File.Delete(Path.Combine(Home,"bridge.json")); }catch{}Log("Stopped");
    }
    void StopChild() { if(child!=null){try{if(!child.HasExited)child.Kill();}catch{}child.Dispose();child=null;}session=-1; }
    void Monitor() {
        while(!stopped) {
            try { lock(gate) {
                int active=unchecked((int)Native.WTSGetActiveConsoleSessionId());
                if(active<0 || !OwnerSession(active)){StopChild();snapshot=Encode(Obj("available",true,"pending",false,"reason","owner-not-active"));}
                else {
                    if(child==null || child.HasExited || session!=active){StopChild();pipe="Tethium.Uac."+RandomId();child=Native.LaunchWorker(active,pipe);session=active;Log("Worker "+child.Id+" session "+active);}
                    snapshot=Call(Obj("command","state"));
                }
            }}catch(Exception e){lock(gate){StopChild();snapshot=Encode(Obj("available",false,"pending",false,"error","Помощник UAC переподключается"));}Log(e.Message);}
            Thread.Sleep(750);
        }
    }
    bool OwnerSession(int id) {
        IntPtr token;if(!Native.WTSQueryUserToken((uint)id,out token))return false;
        try { using(var identity=new WindowsIdentity(token))return identity.User.Value==owner; }finally{Native.CloseHandle(token);}
    }
    string Call(Dictionary<string,object> command) {
        using(var channel=new NamedPipeClientStream(".",pipe,PipeDirection.InOut,PipeOptions.Asynchronous)) {
            channel.Connect(1500);
            var pending=Task.Run(()=>{using(var writer=new StreamWriter(channel,new UTF8Encoding(false),4096,true))using(var reader=new StreamReader(channel,Encoding.UTF8,false,4096,true)) { writer.WriteLine(Encode(command));writer.Flush();return reader.ReadLine(); }});
            if(!pending.Wait(4000))throw new IOException("UAC worker timeout");
            string response=pending.Result;if(String.IsNullOrEmpty(response)||response.Length>262144)throw new IOException("Invalid worker reply");return response;
        }
    }
    void Listen() {
        while(!stopped) { try { var context=http.GetContext();Task.Run(()=>Handle(context)); }catch{if(stopped)return;Thread.Sleep(200);} }
    }
    void Handle(HttpListenerContext context) {
        var request=context.Request;int status=200;string result;
        try {
            if(!IPAddress.IsLoopback(request.RemoteEndPoint.Address)||request.Headers["Authorization"]!="Bearer "+secret)throw new UnauthorizedAccessException();
            if(request.HttpMethod=="GET" && request.Url.AbsolutePath=="/state")result=snapshot;
            else if(request.HttpMethod=="POST" && request.Url.AbsolutePath=="/answer") {
                if(request.ContentLength64<1||request.ContentLength64>2048)throw new ArgumentException("Invalid answer");
                string input;using(var reader=new StreamReader(request.InputStream,Encoding.UTF8))input=reader.ReadToEnd();var body=Decode(input);
                lock(gate) {
                    if(child==null||child.HasExited)throw new InvalidOperationException("Окно уже закрыто");
                    result=Call(Obj("command","answer","id",Text(body,"id"),"action",Text(body,"action")));snapshot=Call(Obj("command","state"));
                }
            }else {status=404;result=Encode(Obj("error","Unknown operation"));}
        }catch(UnauthorizedAccessException){status=403;result=Encode(Obj("error","Denied"));}
        catch(Exception e){status=409;result=Encode(Obj("error",e.Message));}
        try {var bytes=Encoding.UTF8.GetBytes(result);context.Response.StatusCode=status;context.Response.ContentType="application/json; charset=utf-8";context.Response.Headers["Cache-Control"]="no-store";context.Response.ContentLength64=bytes.Length;context.Response.OutputStream.Write(bytes,0,bytes.Length);}catch{}finally{context.Response.Close();}
    }

    sealed class DialogState {
        internal string Id, Fingerprint;
        internal IntPtr Window;
        internal AutomationElement Root;
        internal Dictionary<string,AutomationElement> Actions=new Dictionary<string,AutomationElement>();
        internal Dictionary<string,object> Value;
    }
    static DialogState current;
    static void Worker(string name) {
        if(!WindowsIdentity.GetCurrent().IsSystem)throw new InvalidOperationException("SYSTEM required");
        // LaunchWorker assigns WinSta0\\Winlogon before the CLR or UIA creates any windows.
        if(Native.DesktopName(Native.GetThreadDesktop(Native.GetCurrentThreadId()))!="Winlogon")throw new InvalidOperationException("Secure desktop required");
        var security=new PipeSecurity();security.SetAccessRuleProtection(true,false);security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid,null),PipeAccessRights.FullControl,AccessControlType.Allow));
        while(true)using(var channel=new NamedPipeServerStream(name,PipeDirection.InOut,1,PipeTransmissionMode.Byte,PipeOptions.None,4096,4096,security)) {
            channel.WaitForConnection();
            using(var reader=new StreamReader(channel,Encoding.UTF8,false,4096,true))using(var writer=new StreamWriter(channel,new UTF8Encoding(false),4096,true)) {
                string result;
                try {
                    string input=reader.ReadLine();if(input==null||input.Length>2048)throw new ArgumentException("Invalid command");var command=Decode(input);string op=Text(command,"command");
                    if(op=="state")result=Encode(ReadDialog());
                    else if(op=="answer")result=Encode(Answer(Text(command,"id"),Text(command,"action")));
                    else throw new ArgumentException("Unknown command");
                }catch(Exception e){current=null;result=Encode(Obj("available",false,"pending",false,"error",e.Message));}
                writer.WriteLine(result);writer.Flush();
            }
        }
    }
    static bool SecureInput() {
        IntPtr desktop=Native.OpenInputDesktop(0,false,0x0001);if(desktop==IntPtr.Zero)return false;
        try{return Native.DesktopName(desktop)=="Winlogon";}finally{Native.CloseDesktop(desktop);}
    }
    static IntPtr ConsentWindow() {
        IntPtr found=IntPtr.Zero;
        Native.EnumWindows((hwnd,unused)=>{
            if(!Native.IsWindowVisible(hwnd))return true;
            uint id;Native.GetWindowThreadProcessId(hwnd,out id);
            try { using(var process=Process.GetProcessById((int)id)) {
                if(process.SessionId!=Process.GetCurrentProcess().SessionId)return true;
                string expected=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows),"System32","consent.exe");
                if(!String.Equals(process.MainModule.FileName,expected,StringComparison.OrdinalIgnoreCase))return true;
                IntPtr token;if(!Native.OpenProcessToken(process.Handle,8,out token))return true;
                try{using(var identity=new WindowsIdentity(token))if(!identity.IsSystem)return true;}finally{Native.CloseHandle(token);}
                found=hwnd;return false;
            }}catch{return true;}
        },IntPtr.Zero);return found;
    }
    static object ReadDialog() {
        if(!SecureInput()){current=null;return Obj("available",true,"pending",false);}
        IntPtr window=ConsentWindow();if(window==IntPtr.Zero){current=null;return Obj("available",true,"pending",false);}
        var root=AutomationElement.FromHandle(window);
        uint processId;Native.GetWindowThreadProcessId(window,out processId);
        var texts=new List<string>();var elements=new List<AutomationElement>();var signature=new StringBuilder(window.ToString());bool credentials=false;
        using(var process=Process.GetProcessById((int)processId))signature.Append(':').Append(processId).Append(':').Append(process.StartTime.ToUniversalTime().Ticks);
        Walk(root,texts,elements,signature,ref credentials,0,new int[]{0});
        string fingerprint;using(var hash=SHA256.Create())fingerprint=Convert.ToBase64String(hash.ComputeHash(Encoding.UTF8.GetBytes(signature.ToString())));
        if(current!=null&&current.Window==window&&current.Fingerprint==fingerprint)return current.Value;
        var next=new DialogState{Id=RandomId(),Fingerprint=fingerprint,Window=window,Root=root};var actions=new List<object>();
        if(!credentials)foreach(var element in elements) {
            string id=RandomId();next.Actions.Add(id,element);actions.Add(Obj("id",id,"label",element.Current.Name));
        }
        next.Value=Obj("available",true,"pending",true,"id",next.Id,"title",root.Current.Name,"texts",texts,"actions",actions,"credentials",credentials);
        current=next;return next.Value;
    }
    static void Walk(AutomationElement element,List<string> texts,List<AutomationElement> actions,StringBuilder signature,ref bool credentials,int depth,int[] count) {
        if(depth>16||++count[0]>256)return;
        var info=element.Current;string name=info.IsPassword?"":info.Name;
        if(name.Length>4000)name=name.Substring(0,4000);
        signature.Append('|').Append(info.ControlType.Id).Append(':').Append(info.AutomationId).Append(':').Append(name).Append(':').Append(info.IsEnabled).Append(':').Append(info.IsOffscreen);
        foreach(int part in element.GetRuntimeId())signature.Append('/').Append(part);
        if(!info.IsOffscreen&&(info.IsPassword||info.ControlType==ControlType.Edit))credentials=true;
        bool clickable=info.ControlType==ControlType.Button||info.ControlType==ControlType.Hyperlink;
        if(!String.IsNullOrWhiteSpace(name)&&!clickable&&info.ControlType!=ControlType.Edit&&!texts.Contains(name)&&texts.Count<64)texts.Add(name);
        object invoke;if(clickable&&info.IsEnabled&&!info.IsOffscreen&&!String.IsNullOrWhiteSpace(name)&&element.TryGetCurrentPattern(InvokePattern.Pattern,out invoke))actions.Add(element);
        var walker=TreeWalker.ControlViewWalker;
        for(var child=walker.GetFirstChild(element);child!=null;child=walker.GetNextSibling(child))Walk(child,texts,actions,signature,ref credentials,depth+1,count);
    }
    static object Answer(string id,string action) {
        // Rescan immediately. Any changed contents, closed prompt or desktop switch invalidates every old action.
        ReadDialog();AutomationElement target;
        if(current==null||!String.Equals(id,current.Id,StringComparison.Ordinal)||!current.Actions.TryGetValue(action,out target))return Obj("ok",false,"error","Запрос изменился или уже закрыт. Откройте текущий запрос.");
        if(!SecureInput()||ConsentWindow()!=current.Window||!target.Current.IsEnabled||target.Current.IsOffscreen)return Obj("ok",false,"error","Окно уже недоступно");
        var expected=current;current=null; // consume before invoking; a network retry cannot approve the next prompt
        ((InvokePattern)target.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
        return Obj("ok",true);
    }
    static void SelfTest() {
        if(Text(Decode(Encode(Obj("id","abc","label","Да"))),"label")!="Да")throw new Exception("JSON roundtrip");
        current=null;var result=(Dictionary<string,object>)Answer("expired","missing");if((bool)result["ok"])throw new Exception("Stale answer accepted");
        File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"self-test.ok"),"JSON roundtrip; missing/stale dialog rejected");
    }

    static class Native {
        internal delegate bool EnumProc(IntPtr window,IntPtr param);
        [DllImport("user32.dll")] internal static extern bool EnumWindows(EnumProc proc,IntPtr param);
        [DllImport("user32.dll")] internal static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")] internal static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
        [DllImport("user32.dll")] internal static extern IntPtr GetThreadDesktop(uint thread);
        [DllImport("kernel32.dll")] internal static extern uint GetCurrentThreadId();
        [DllImport("user32.dll",SetLastError=true)] internal static extern IntPtr OpenInputDesktop(uint flags,bool inherit,uint access);
        [DllImport("user32.dll")] internal static extern bool CloseDesktop(IntPtr desktop);
        [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool GetUserObjectInformation(IntPtr handle,int index,StringBuilder value,int length,out int needed);
        internal static string DesktopName(IntPtr desktop){int needed;var name=new StringBuilder(128);return GetUserObjectInformation(desktop,2,name,256,out needed)?name.ToString():"";}
        [DllImport("kernel32.dll")] internal static extern uint WTSGetActiveConsoleSessionId();
        [DllImport("wtsapi32.dll",SetLastError=true)] internal static extern bool WTSQueryUserToken(uint session,out IntPtr token);
        [DllImport("advapi32.dll",SetLastError=true)] internal static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
        [DllImport("advapi32.dll",SetLastError=true)] static extern bool DuplicateTokenEx(IntPtr existing,uint access,IntPtr attributes,int level,int type,out IntPtr token);
        [DllImport("advapi32.dll",SetLastError=true)] static extern bool SetTokenInformation(IntPtr token,int type,ref int value,int size);
        [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool LookupPrivilegeValue(string system,string name,out long luid);
        [StructLayout(LayoutKind.Sequential,Pack=4)] struct Privilege { internal int Count;internal long Luid;internal int Attributes; }
        [DllImport("advapi32.dll",SetLastError=true)] static extern bool AdjustTokenPrivileges(IntPtr token,bool disable,ref Privilege privileges,int length,IntPtr previous,IntPtr needed);
        [DllImport("kernel32.dll")] internal static extern bool CloseHandle(IntPtr handle);
        [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct Startup {
            internal int cb;internal string reserved,desktop,title;internal int x,y,width,height,xChars,yChars,fill,flags;internal short show,reserved2;internal IntPtr reservedPtr,input,output,error;
        }
        [StructLayout(LayoutKind.Sequential)] struct ProcessInfo { internal IntPtr process,thread;internal int pid,tid; }
        [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcessAsUser(IntPtr token,string app,StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr environment,string directory,ref Startup startup,out ProcessInfo result);
        internal static void EnablePrivileges() {
            IntPtr source;if(!OpenProcessToken(Process.GetCurrentProcess().Handle,0x0008|0x0020,out source))throw new System.ComponentModel.Win32Exception();
            try { foreach(string name in new[]{"SeTcbPrivilege","SeAssignPrimaryTokenPrivilege","SeIncreaseQuotaPrivilege"}){long luid;if(!LookupPrivilegeValue(null,name,out luid))throw new System.ComponentModel.Win32Exception();var value=new Privilege{Count=1,Luid=luid,Attributes=2};if(!AdjustTokenPrivileges(source,false,ref value,0,IntPtr.Zero,IntPtr.Zero)||Marshal.GetLastWin32Error()!=0)throw new System.ComponentModel.Win32Exception();} }finally{CloseHandle(source);}
        }
        internal static Process LaunchWorker(int session,string pipe) {
            IntPtr source;if(!OpenProcessToken(Process.GetCurrentProcess().Handle,0x0002|0x0008|0x0020,out source))throw new System.ComponentModel.Win32Exception();
            IntPtr token=IntPtr.Zero;
            try {
                if(!DuplicateTokenEx(source,0xF01FF,IntPtr.Zero,2,1,out token)||!SetTokenInformation(token,12,ref session,4))throw new System.ComponentModel.Win32Exception();
                string exe=Process.GetCurrentProcess().MainModule.FileName;var startup=new Startup{cb=Marshal.SizeOf(typeof(Startup)),desktop=@"winsta0\Winlogon",flags=1,show=0};ProcessInfo result;
                if(!CreateProcessAsUser(token,exe,new StringBuilder("\""+exe+"\" --worker "+pipe),IntPtr.Zero,IntPtr.Zero,false,0x08000000,IntPtr.Zero,Path.GetDirectoryName(exe),ref startup,out result))throw new System.ComponentModel.Win32Exception();
                CloseHandle(result.process);CloseHandle(result.thread);return Process.GetProcessById(result.pid);
            }finally{if(token!=IntPtr.Zero)CloseHandle(token);CloseHandle(source);}
        }
    }
}
