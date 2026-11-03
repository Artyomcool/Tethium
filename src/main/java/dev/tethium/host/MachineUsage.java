package dev.tethium.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tethium.json.Json;
import java.util.*;

/** Live machine totals, independent of historical session accounting and CPU limits. */
final class MachineUsage {
    private Map<String,Long> previous=Map.of();
    private long previousAt;
    private volatile JsonNode value=Json.object();
    JsonNode snapshot(){return value;}
    void sample(List<CpuSampler.Sample> samples,Map<Long,CpuSampler.Assignment> assigned,double totalCpu,long totalMemory,long freeMemory,long now,int cores){
        var ours=new HashSet<>(assigned.keySet());
        var sessions=new LinkedHashMap<String,Memory>();var projects=new LinkedHashMap<String,Memory>();var all=new Memory();
        for(var p:samples){var assignment=assigned.get(p.pid());if(assignment==null||assignment.owner()==null)continue;
            var owner=assignment.owner();all.add(p);
            sessions.computeIfAbsent(owner.session(),id->new Memory()).add(p);
            projects.computeIfAbsent(owner.project(),id->new Memory()).add(p);
        }
        for(var p:samples){String command=p.command().toLowerCase(Locale.ROOT);
            if(p.pid()==ProcessHandle.current().pid()||command.contains("dev.tethium.desktop.desktopmain")||command.contains("dev.tethium.resources.jobkeeper")||command.contains("dev.tethium.host.hostmain"))ours.add(p.pid());
        }
        var byPid=new HashMap<Long,CpuSampler.Sample>();samples.forEach(p->byPid.put(p.pid(),p));
        boolean changed;do{changed=false;for(var p:samples){var parent=byPid.get(p.parent());if(parent!=null&&parent.started()<=p.started()&&ours.contains(parent.pid()))changed|=ours.add(p.pid());}}while(changed);
        var next=new HashMap<String,Long>();long delta=0,memory=0;int memoryMeasured=0,memoryUnmeasured=0;
        for(var p:samples){next.put(p.key(),p.nanos());if(!ours.contains(p.pid()))continue;
            Long before=previous.get(p.key());if(before!=null)delta+=Math.max(0,p.nanos()-before);
            else if(previousAt>0&&p.started()>=previousAt)delta+=p.nanos();
            if(p.memory()<0)memoryUnmeasured++;else{memoryMeasured++;memory+=p.memory();}
        }
        Double cpu=previousAt==0||now<=previousAt?null:Math.clamp(delta/((now-previousAt)*1_000_000.0*Math.max(1,cores))*100,0,100);
        long used=Math.max(0,totalMemory-freeMemory);memory=Math.min(used,memory);
        boolean memoryKnown=memoryMeasured>0||memoryUnmeasured==0;
        value=Json.object("updatedAt",now,"cpuOurs",cpu,"cpuOther",cpu==null||totalCpu<0?null:Math.max(0,totalCpu*100-cpu),"cpuTotal",totalCpu<0?null:totalCpu*100,
            "memoryOurs",memoryKnown?memory:null,"memoryOther",memoryKnown?Math.max(0,used-memory):null,"memoryUsed",used,"memoryTotal",totalMemory,"memoryUnmeasuredProcesses",memoryUnmeasured,
            "memory",Json.object("totalBytes",all.bytes,"processes",all.processes,"unmeasuredProcesses",all.unmeasured,
                "sessions",rows(sessions),"projects",rows(projects)));
        previous=next;previousAt=now;
    }
    private static final class Memory {
        long bytes;int processes,unmeasured;
        void add(CpuSampler.Sample p){processes++;if(p.memory()<0)unmeasured++;else bytes+=p.memory();}
    }
    private static JsonNode rows(Map<String,Memory> groups){
        var result=Json.object();groups.forEach((id,m)->result.set(id,Json.object("bytes",m.bytes,"processes",m.processes,"unmeasuredProcesses",m.unmeasured)));return result;
    }
}
