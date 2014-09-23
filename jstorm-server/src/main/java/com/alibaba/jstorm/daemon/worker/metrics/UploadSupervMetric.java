package  com.alibaba.jstorm.daemon.worker.metrics;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

import org.apache.log4j.Logger;

import com.alibaba.jstorm.callback.RunnableCallback;
import com.alibaba.jstorm.cluster.StormBase;
import com.alibaba.jstorm.cluster.StormClusterState;
import com.alibaba.jstorm.cluster.StormMonitor;
import com.alibaba.jstorm.daemon.supervisor.Supervisor;
import com.alibaba.jstorm.daemon.supervisor.SupervisorInfo;
import com.alibaba.jstorm.daemon.worker.WorkerMetricInfo;
import com.alibaba.jstorm.metric.MetricDef;
import com.alibaba.jstorm.task.TaskMetricInfo;
import com.alibaba.jstorm.task.Assignment;

public class UploadSupervMetric extends RunnableCallback {
	private static Logger LOG = Logger.getLogger(UploadSupervMetric.class);
    
    private static final String TASK_MONITOR_NAME = "jstorm_task_metrics";
    private static final String WORKER_MONITOR_NAME = "jstorm_worker_metrics";
	
    private AtomicBoolean active;
	private Integer result;
	private int frequence;
    
	private String supervisorId;
	private String hostName;
	private StormClusterState cluster;
	private AlimonitorClient client = new AlimonitorClient();
	
	List<Map<String, Object>> jsonMsgTasks = new ArrayList<Map<String, Object>>();
	List<Map<String, Object>> jsonMsgWorkers = new ArrayList<Map<String, Object>>();
	
	public UploadSupervMetric(StormClusterState cluster, String supervisorId, AtomicBoolean active, int frequence) {
		this.active = active;
		this.frequence = frequence;
		this.result = null;
		this.cluster = cluster;
		this.supervisorId = supervisorId;
		try {
		    SupervisorInfo supervisorInfo = cluster.supervisor_info(supervisorId);
		    this.hostName = supervisorInfo.getHostName();
		} catch (Exception e) {
			LOG.error("Failed to get hostname for supervisorID=" + supervisorId);
		}
	}
	
	@Override
	public Object getResult() {
		return result;
	}

	@Override
	public void run() {
		sendMetricsData();;
		if (active.get()) {
			this.result = frequence;
		} else {
			this.result = -1;

		}
	}
	
	
	public void sendMetricsData() {
		
		try {
		    List<String> topologys = cluster.active_storms();
		    
		    for (String topologyId : topologys) {
		    	StormMonitor monitor = cluster.get_storm_monitor(topologyId);
		    	if (monitor == null) continue;
		    	boolean metricPerf = monitor.getMetrics();
		    	
		    	Assignment assignment = cluster.assignment_info(topologyId, null);
		    	
		    	if (assignment != null) {
		    		Set<Integer> taskSet = new HashSet<Integer>();
		    		Set<Integer> workerSet = new HashSet<Integer>();
		    		//Retrieve task set
		    		Set<Integer> tempTaskSet = assignment.getCurrentSuperviosrTasks(supervisorId);
		    	    taskSet.addAll(tempTaskSet);
		    		
		    		//Retrieve worker set
		    		Set<Integer> tempWorkerSet = assignment.getCurrentSuperviosrWorkers(supervisorId);
		    		workerSet.addAll(tempWorkerSet);
		    		
		    		//Build KV Map for AliMonitor
		    		buildTaskJsonMsg(topologyId, taskSet, metricPerf);
		    		buildWorkerJsonMsg(topologyId, workerSet, metricPerf);
		    	}
		    }
		    
		    if (jsonMsgTasks.size() != 0) {
		    	client.setMonitorName(TASK_MONITOR_NAME);
		    	client.sendRequest(0, "", jsonMsgTasks);
		    }
		    
		    if (jsonMsgWorkers.size() != 0) {
		    	client.setMonitorName(WORKER_MONITOR_NAME);
		    	client.sendRequest(0, "", jsonMsgWorkers);
		    }
		    
		    jsonMsgTasks.clear();
		    jsonMsgWorkers.clear();
		    
		} catch (Exception e) {
			LOG.error("Failed to upload worker&task metrics data", e);
			jsonMsgTasks.clear();
		    jsonMsgWorkers.clear();
		}
	}

	public void buildTaskJsonMsg(String topologyId, Set<Integer> taskSet, boolean metricPerf) {
		for (Integer taskId : taskSet) {
			try {
			    TaskMetricInfo taskMetric = cluster.get_task_metric(topologyId, taskId);
			    if (taskMetric == null) continue;
			    
			    // Task KV structure
			    Map<String, Object> taskKV = new HashMap<String, Object>();
			    taskKV.put("Topology_Name", topologyId);
			    taskKV.put("Task_Id", String.valueOf(taskId));
			    taskKV.put("Component", taskMetric.getComponent());
			    taskKV.putAll(taskMetric.getGaugeData());
			    taskKV.putAll(taskMetric.getCounterData());
			    taskKV.putAll(taskMetric.getMeterData());
			    if (metricPerf == true) {
			        taskKV.putAll(taskMetric.getTimerData());
			        taskKV.putAll(taskMetric.getHistogramData());
			    }
			    
			    jsonMsgTasks.add(taskKV);
			} catch (Exception e) {
				LOG.error("Failed to buildTaskJsonMsg, taskID=" + taskId + ", e=" + e);
			}
		}
	}
	
	public void buildWorkerJsonMsg(String topologyId, Set<Integer> workerSet, boolean metricPerf) {
		String workerId = null;
		for (Integer port: workerSet) {
			try {
				workerId = hostName + ":" + port;
				WorkerMetricInfo workerMetric = cluster.get_worker_metric(topologyId, workerId);
				if (workerMetric == null) continue;
				
				Map<String, Object> workerKV = new HashMap<String, Object>();
                
				workerKV.put("Topology_Name", topologyId);
				workerKV.put("Port", String.valueOf(port));
				workerKV.put(MetricDef.MEMORY_USED, workerMetric.getUsedMem());
				workerKV.put(MetricDef.CPU_USED_RATIO, workerMetric.getUsedCpu());
				
				workerKV.putAll(workerMetric.getGaugeData());
				workerKV.putAll(workerMetric.getCounterData());
				workerKV.putAll(workerMetric.getMeterData());
				
				if (metricPerf == true)
				{
                    workerKV.putAll(workerMetric.getTimerData());
                    workerKV.putAll(workerMetric.getHistogramData());
				}
				
				jsonMsgWorkers.add(workerKV);
			} catch (Exception e) {
				LOG.error("Failed to buildWorkerJsonMsg, workerId=" + workerId + ", e=" + e);
			}
		}
	}

	public void clean() {
	}
}