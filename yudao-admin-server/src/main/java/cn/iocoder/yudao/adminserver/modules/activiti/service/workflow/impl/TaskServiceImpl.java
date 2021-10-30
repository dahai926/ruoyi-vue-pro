package cn.iocoder.yudao.adminserver.modules.activiti.service.workflow.impl;

import cn.iocoder.yudao.adminserver.modules.activiti.controller.workflow.vo.*;
import cn.iocoder.yudao.adminserver.modules.activiti.convert.oa.OaLeaveConvert;
import cn.iocoder.yudao.adminserver.modules.activiti.dal.dataobject.oa.OaLeaveDO;
import cn.iocoder.yudao.adminserver.modules.activiti.dal.mysql.oa.OaLeaveMapper;
import cn.iocoder.yudao.adminserver.modules.activiti.service.oa.OaLeaveService;
import cn.iocoder.yudao.adminserver.modules.activiti.service.workflow.TaskService;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.common.collect.ImmutableMap;
import org.activiti.api.process.runtime.ProcessRuntime;
import org.activiti.api.runtime.shared.query.Page;
import org.activiti.api.runtime.shared.query.Pageable;
import org.activiti.api.task.model.Task;
import org.activiti.api.task.model.builders.ClaimTaskPayloadBuilder;
import org.activiti.api.task.model.builders.GetTasksPayloadBuilder;
import org.activiti.api.task.model.builders.TaskPayloadBuilder;
import org.activiti.api.task.runtime.TaskRuntime;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.Process;
import org.activiti.engine.HistoryService;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricVariableInstance;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.task.Comment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TaskServiceImpl implements TaskService {

    @Resource
    private  TaskRuntime taskRuntime;

    @Resource
    private org.activiti.engine.TaskService activitiTaskService;

    @Resource
    private HistoryService  historyService;

    @Resource
    private RepositoryService repositoryService;

    @Resource
    private OaLeaveMapper leaveMapper;

    private static Map<String,String>  taskVariable =  ImmutableMap.<String,String>builder()
                    .put("deptLeaderVerify","deptLeaderApproved")
                    .put("hrVerify","hrApproved")
                    .build();

    public TaskServiceImpl() {

    }

    @Override
    public PageResult<TodoTaskRespVO> getTodoTaskPage(TodoTaskPageReqVO pageReqVO) {
        final LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        final Pageable pageable = Pageable.of((pageReqVO.getPageNo() - 1) * pageReqVO.getPageSize(), pageReqVO.getPageSize());
        Page<Task> pageTasks = taskRuntime.tasks(pageable);
        List<Task> tasks = pageTasks.getContent();
        int totalItems = pageTasks.getTotalItems();
        final List<TodoTaskRespVO> respVOList = tasks.stream().map(task -> {
            TodoTaskRespVO respVO = new TodoTaskRespVO();
            respVO.setId(task.getId());
            final ProcessDefinition definition = repositoryService.getProcessDefinition(task.getProcessDefinitionId());
            respVO.setProcessName(definition.getName());
            respVO.setProcessKey(definition.getKey());
            respVO.setBusinessKey(task.getBusinessKey());
            respVO.setStatus(task.getAssignee() == null ? 1 : 2);
            return respVO;
        }).collect(Collectors.toList());
        return new PageResult(respVOList, Long.valueOf(totalItems));
    }


    @Override
    public void claimTask(String taskId) {
        taskRuntime.claim(new ClaimTaskPayloadBuilder()
                                .withTaskId(taskId)
                                .withAssignee(SecurityFrameworkUtils.getLoginUser().getUsername())
                                .build());
    }

    @Override
    public void getTaskHistory(String taskId) {

        final List<HistoricProcessInstance> list = historyService.createHistoricProcessInstanceQuery().
                processInstanceId("8e2801fc-1a38-11ec-98ce-74867a13730f").list();

    }

    @Override
    @Transactional
    public void completeTask(TaskReqVO taskReq) {
        final Task task = taskRuntime.task(taskReq.getTaskId());

        final Map<String, Object> variables = taskReq.getVariables();

        activitiTaskService.addComment(taskReq.getTaskId(), task.getProcessInstanceId(), taskReq.getComment());

        taskRuntime.complete(TaskPayloadBuilder.complete().withTaskId(taskReq.getTaskId())
                .withVariables(taskReq.getVariables())
                .build());

        if(variables.containsValue(Boolean.FALSE)){
            final String businessKey = task.getBusinessKey();
            UpdateWrapper<OaLeaveDO> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", Long.valueOf(businessKey));
            OaLeaveDO updateDo = new OaLeaveDO();
            updateDo.setStatus(2);
            leaveMapper.update(updateDo, updateWrapper);
        }

    }

//    @Override
//    public void flowImage(String taskId, HttpServletResponse response) {
//
//        final Task task = taskRuntime.task(taskId);
//        BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());
//        final Process process = bpmnModel.getMainProcess();
//        ProcessDefinitionEntity processDefinition = repositoryService.createProcessDefinitionQuery().processDefinitionId(task.getProcessDefinitionId()).singleResult();
//        List<String> activeActivityIds = runtimeService.getActiveActivityIds(executionId);
//        List<String> highLightedFlows = getHighLightedFlows(processDefinition, processInstance.getId());
//        ProcessDiagramGenerator diagramGenerator = processEngineConfiguration.getProcessDiagramGenerator();
//        InputStream imageStream =diagramGenerator.generateDiagram(bpmnModel, "png", activeActivityIds, highLightedFlows);
//
//        // 输出资源内容到相应对象
//        byte[] b = new byte[1024];
//        int len;
//        while ((len = imageStream.read(b, 0, 1024)) != -1) {
//            response.getOutputStream().write(b, 0, len);
//        }
//    }

    @Override
    public TaskHandleVO getTaskSteps(TaskQueryReqVO taskQuery) {
        TaskHandleVO handleVO = new TaskHandleVO();

        String processKey = taskQuery.getProcessKey();
        if ("leave".equals(processKey)) {
            String businessKey = taskQuery.getBusinessKey();
            final OaLeaveDO leave = leaveMapper.selectById(Long.valueOf(businessKey));
            handleVO.setFormObject( OaLeaveConvert.INSTANCE.convert(leave));
        }

        final Task task = taskRuntime.task(taskQuery.getTaskId());
        final String taskDefKey = task.getTaskDefinitionKey();
        final String variableName = Optional.ofNullable(taskVariable.get(taskDefKey)).orElse("");


        handleVO.setTaskVariable(variableName);
        List<TaskStepVO> steps = getTaskSteps(task.getProcessInstanceId());

        handleVO.setHistoryTask(steps);
        return handleVO;
    }


    private List<TaskStepVO> getTaskSteps(String processInstanceId) {

        List<TaskStepVO> steps = new ArrayList<>();

        List<HistoricActivityInstance> finished = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityType("userTask")
                .finished()
                .orderByHistoricActivityInstanceStartTime().asc().list();

        finished.forEach(instance->{
            TaskStepVO step = new TaskStepVO();
            step.setStepName(instance.getActivityName());
            step.setStartTime(instance.getStartTime());
            step.setEndTime(instance.getEndTime());
            step.setAssignee(instance.getAssignee());
            final List<Comment> comments = activitiTaskService.getTaskComments(instance.getTaskId());
            if(comments.size()>0){
                step.setComment(comments.get(0).getFullMessage());
            }else{
                step.setComment("");
            }
            steps.add(step);
        });

        List<HistoricActivityInstance> unfinished = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityType("userTask")
                .unfinished().list();

        if(unfinished.size()>0) {

            final HistoricActivityInstance unFinishedActiviti = unfinished.get(0);
            TaskStepVO step = new TaskStepVO();
            step.setStepName(unFinishedActiviti.getActivityName());
            step.setStartTime(unFinishedActiviti.getStartTime());
            step.setEndTime(unFinishedActiviti.getEndTime());
            step.setAssignee(Optional.ofNullable(unFinishedActiviti.getAssignee()).orElse(""));
            step.setComment("");
            steps.add(step);
        }
        return steps;
    }


    @Override
    public List<TaskStepVO> getHistorySteps(String processInstanceId) {

        return getTaskSteps(processInstanceId);
    }



//    private List<String> getHighLightedFlows(ProcessDefinitionEntity processDefinition, String processInstanceId) {
//
//        List<String> highLightedFlows = new ArrayList<String>();
//        List<HistoricActivityInstance> historicActivityInstances = historyService
//                .createHistoricActivityInstanceQuery()
//                .processInstanceId(processInstanceId)
//                .orderByHistoricActivityInstanceStartTime().asc().list();
//
//        List<String> historicActivityInstanceList = new ArrayList<String>();
//        for (HistoricActivityInstance hai : historicActivityInstances) {
//            historicActivityInstanceList.add(hai.getActivityId());
//        }

//        // add current activities to list
//        List<String> highLightedActivities = runtimeService.getActiveActivityIds(processInstanceId);
//        historicActivityInstanceList.addAll(highLightedActivities);

        // activities and their sequence-flows
//        for (ActivityImpl activity : processDefinition.getActivities()) {
//            int index = historicActivityInstanceList.indexOf(activity.getId());
//
//            if (index >= 0 && index + 1 < historicActivityInstanceList.size()) {
//                List<PvmTransition> pvmTransitionList = activity
//                        .getOutgoingTransitions();
//                for (PvmTransition pvmTransition : pvmTransitionList) {
//                    String destinationFlowId = pvmTransition.getDestination().getId();
//                    if (destinationFlowId.equals(historicActivityInstanceList.get(index + 1))) {
//                        highLightedFlows.add(pvmTransition.getId());
//                    }
//                }
//            }
//        }
//        return highLightedFlows;
//    }
}