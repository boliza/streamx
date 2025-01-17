/*
 * Copyright (c) 2019 The StreamX Project
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.streamxhub.streamx.console.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.streamxhub.streamx.common.conf.ConfigConst;
import com.streamxhub.streamx.common.enums.DevelopmentMode;
import com.streamxhub.streamx.common.enums.ExecutionMode;
import com.streamxhub.streamx.common.enums.ResolveOrder;
import com.streamxhub.streamx.common.util.*;
import com.streamxhub.streamx.console.base.domain.Constant;
import com.streamxhub.streamx.console.base.domain.RestRequest;
import com.streamxhub.streamx.console.base.utils.CommonUtil;
import com.streamxhub.streamx.console.base.utils.SortUtil;
import com.streamxhub.streamx.console.base.utils.WebUtil;
import com.streamxhub.streamx.console.core.annotation.RefreshCache;
import com.streamxhub.streamx.console.core.dao.ApplicationMapper;
import com.streamxhub.streamx.console.core.entity.*;
import com.streamxhub.streamx.console.core.enums.*;
import com.streamxhub.streamx.console.core.metrics.flink.JobsOverview;
import com.streamxhub.streamx.console.core.service.*;
import com.streamxhub.streamx.console.core.task.FlinkTrackingTask;
import com.streamxhub.streamx.console.system.authentication.ServerUtil;
import com.streamxhub.streamx.flink.common.conf.ParameterCli;
import com.streamxhub.streamx.flink.submit.FlinkSubmit;
import com.streamxhub.streamx.flink.submit.SubmitRequest;
import com.streamxhub.streamx.flink.submit.SubmitResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.TaskManagerOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import scala.collection.JavaConversions;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * @author benjobs
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true, rollbackFor = Exception.class)
public class ApplicationServiceImpl extends ServiceImpl<ApplicationMapper, Application>
        implements ApplicationService {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ApplicationBackUpService backUpService;

    @Autowired
    private ApplicationConfigService configService;

    @Autowired
    private FlinkSqlService flinkSqlService;

    @Autowired
    private SavePointService savePointService;

    @Autowired
    private ApplicationLogService applicationLogService;

    @Autowired
    private SettingService settingService;

    @Autowired
    private ServerUtil serverUtil;

    private final String workspace = ConfigConst.APP_WORKSPACE();

    private final Map<Long, Long> tailOutMap = new ConcurrentHashMap<>();

    private final Map<Long, StringBuilder> tailBuffer = new ConcurrentHashMap<>();

    private final Map<Long, Boolean> tailBeginning = new ConcurrentHashMap<>();

    private final String APP_UPLOADS = HdfsUtils.getDefaultFS().concat(ConfigConst.APP_UPLOADS());

    @Autowired
    private SimpMessageSendingOperations simpMessageSendingOperations;

    private final ExecutorService executorService = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * 2,
            200,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            ThreadUtils.threadFactory("streamx-deploy-executor"),
            new ThreadPoolExecutor.AbortPolicy()
    );

    @PostConstruct
    public void resetOptionState() {
        this.baseMapper.resetOptionState();
    }

    @Override
    public Map<String, Serializable> dashboard() {
        JobsOverview.Task overview = new JobsOverview.Task();
        AtomicInteger totalJmMemory = new AtomicInteger(0);
        AtomicInteger totalTmMemory = new AtomicInteger(0);
        AtomicInteger totalTm = new AtomicInteger(0);
        AtomicInteger totalSlot = new AtomicInteger(0);
        AtomicInteger availableSlot = new AtomicInteger(0);
        AtomicInteger runningJob = new AtomicInteger(0);

        FlinkTrackingTask.getAllTrackingApp().forEach((k, v) -> {
            if (v.getJmMemory() != null) {
                totalJmMemory.addAndGet(v.getJmMemory());
            }

            if (v.getTmMemory() != null) {
                totalTmMemory.addAndGet(v.getTmMemory());
            }

            if (v.getTotalTM() != null) {
                totalTm.addAndGet(v.getTotalTM());
            }

            if (v.getTotalSlot() != null) {
                totalSlot.addAndGet(v.getTotalSlot());
            }

            if (v.getAvailableSlot() != null) {
                availableSlot.addAndGet(v.getAvailableSlot());
            }

            if (v.getState() == FlinkAppState.RUNNING.getValue()) {
                runningJob.incrementAndGet();
            }

            JobsOverview.Task task = v.getOverview();
            if (task != null) {
                overview.setTotal(overview.getTotal() + task.getTotal());
                overview.setCreated(overview.getCreated() + task.getCreated());
                overview.setScheduled(overview.getScheduled() + task.getScheduled());
                overview.setDeploying(overview.getDeploying() + task.getDeploying());
                overview.setRunning(overview.getRunning() + task.getRunning());
                overview.setFinished(overview.getFinished() + task.getFinished());
                overview.setCanceling(overview.getCanceling() + task.getCanceling());
                overview.setCanceled(overview.getCanceled() + task.getCanceled());
                overview.setFailed(overview.getFailed() + task.getFailed());
                overview.setReconciling(overview.getReconciling() + task.getReconciling());
            }
        });
        Map<String, Serializable> map = new HashMap<>(8);
        map.put("task", overview);
        map.put("jmMemory", totalJmMemory.get());
        map.put("tmMemory", totalTmMemory.get());
        map.put("totalTM", totalTm.get());
        map.put("availableSlot", availableSlot.get());
        map.put("totalSlot", totalSlot.get());
        map.put("runningJob", runningJob.get());

        return map;
    }

    @Override
    public void tailMvnDownloading(Long id) {
        this.tailOutMap.put(id, id);
        // 首次会从buffer里从头读取数据.有且仅有一次.
        this.tailBeginning.put(id, true);
    }

    @Override
    public boolean upload(MultipartFile file) throws IOException {
        String uploadFile = Objects.requireNonNull(file.getOriginalFilename()).concat(APP_UPLOADS.concat("/"));
        //1)检查文件是否存在,md5是否一致.
        if (HdfsUtils.exists(uploadFile)) {
            String md5 = DigestUtils.md5Hex(file.getInputStream());
            //md5一致,则无需在上传.
            if (md5.equals(HdfsUtils.fileMd5(uploadFile))) {
                return true;
            } else {
                //md5不一致,删除
                HdfsUtils.delete(uploadFile);
            }
        }

        //2) 确定需要上传,先上传到本地零时目录
        String temp = WebUtil.getAppDir("temp");
        File saveFile = new File(temp, file.getOriginalFilename());
        // delete when exsit
        if (saveFile.exists()) {
            saveFile.delete();
        }
        // save file to temp dir
        FileUtils.writeByteArrayToFile(saveFile, file.getBytes());
        //3) 从本地temp目录上传到hdfs
        HdfsUtils.upload(saveFile.getAbsolutePath(), APP_UPLOADS, true, true);
        return true;
    }

    @Override
    public void toEffective(Application application) {
        //将Latest的设置为Effective
        ApplicationConfig config = configService.getLatest(application.getId());
        if (config != null) {
            this.configService.toEffective(application.getId(), config.getId());
        }
        if (application.isFlinkSqlJob()) {
            FlinkSql flinkSql = flinkSqlService.getLatest(application.getId());
            if (flinkSql != null) {
                this.flinkSqlService.toEffective(application.getId(), flinkSql.getId());
            }
        }
    }

    @Override
    public IPage<Application> page(Application appParam, RestRequest request) {
        Page<Application> page = new Page<>();
        SortUtil.handlePageSort(request, page, "create_time", Constant.ORDER_DESC, false);
        this.baseMapper.page(page, appParam);
        //瞒天过海,暗度陈仓,偷天换日,鱼目混珠.
        List<Application> records = page.getRecords();
        List<Application> newRecords = new ArrayList<>(records.size());
        records.forEach(x -> {
            Application app = FlinkTrackingTask.getTracking(x.getId());
            if (app != null) {
                app.setProjectName(x.getProjectName());
            }
            newRecords.add(app == null ? x : app);
        });
        page.setRecords(newRecords);
        return page;
    }

    @Override
    public String getYarnName(Application appParam) {
        String[] args = new String[2];
        args[0] = "--name";
        args[1] = appParam.getConfig();
        return ParameterCli.read(args);
    }

    /**
     * 检查当前的jobName在表和yarn中是否已经存在
     *
     * @param appParam
     * @return
     */
    @Override
    public AppExistsState checkExists(Application appParam) {
        boolean inYarn = YarnUtils.isContains(appParam.getJobName());
        if (!inYarn) {
            LambdaQueryWrapper<Application> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Application::getJobName, appParam.getJobName());
            int count = this.baseMapper.selectCount(queryWrapper);
            if (count == 0) {
                return AppExistsState.NO;
            }
        }
        return inYarn ? AppExistsState.IN_YARN : AppExistsState.IN_DB;
    }

    @SneakyThrows
    @Override
    @Transactional(rollbackFor = {Exception.class})
    public boolean create(Application appParam) {
        appParam.setUserId(serverUtil.getUser().getUserId());
        appParam.setState(FlinkAppState.CREATED.getValue());
        appParam.setCreateTime(new Date());
        boolean saved = save(appParam);
        if (saved) {
            if (appParam.isFlinkSqlJob()) {
                FlinkSql flinkSql = new FlinkSql(appParam);
                flinkSqlService.create(flinkSql, true);
            }
            if (appParam.getConfig() != null) {
                configService.create(appParam, true);
            }
            appParam.setBackUp(false);
            appParam.setRestart(false);
            deploy(appParam);
            return true;
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    @RefreshCache
    public boolean update(Application appParam) {
        try {
            Application application = getById(appParam.getId());
            //从db中补全jobType到appParam
            appParam.setJobType(application.getJobType());

            application.setJobName(appParam.getJobName());
            application.setArgs(appParam.getArgs());
            application.setOptions(appParam.getOptions());
            application.setDynamicOptions(appParam.getDynamicOptions());
            application.setDescription(appParam.getDescription());
            application.setResolveOrder(appParam.getResolveOrder());
            application.setExecutionMode(appParam.getExecutionMode());
            // Flink SQL job...
            if (application.isFlinkSqlJob()) {
                updateFlinkSqlJob(application, appParam);
            } else {
                if (application.isStreamXJob()) {
                    configService.update(appParam, application.isRunning());
                } else {
                    application.setJar(appParam.getJar());
                    application.setMainClass(appParam.getMainClass());
                }
                //程序配置已更新需要重启生效
                application.setDeploy(DeployState.NEED_RESTART_AFTER_CONF_UPDATE.get());
            }
            baseMapper.updateById(application);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 更新 FlinkSql 类型的作业.要考虑3个方面<br/>
     * 1. flink Sql是否发生更新 <br/>
     * 2. 依赖是否发生更新<br/>
     * 3. 配置参数是否发生更新<br/>
     *
     * @param application
     * @param appParam
     */
    private void updateFlinkSqlJob(Application application, Application appParam) {
        // 1) 第一步获取当前生效的flinkSql
        FlinkSql effectiveFlinkSql = flinkSqlService.getEffective(application.getId(), true);

        // 2) 判断版本是否发生变化
        boolean versionChanged = !effectiveFlinkSql.getId().equals(appParam.getSqlId());

        //要设置的目标FlinkSql记录
        FlinkSql targetFlinkSql = flinkSqlService.getById(appParam.getSqlId());
        targetFlinkSql.decode();

        // 3) 判断 sql语句是否发生变化
        boolean sqlDifference = !targetFlinkSql.getSql().trim().equals(appParam.getFlinkSQL().trim());

        // 4) 判断 依赖是否发生变化
        Application.Dependency targetDependency = Application.Dependency.jsonToDependency(targetFlinkSql.getDependency());
        Application.Dependency newDependency = appParam.getDependencyObject();
        boolean depsDifference = !targetDependency.eq(newDependency);

        Application.Dependency effectiveDependency = Application.Dependency.jsonToDependency(effectiveFlinkSql.getDependency());
        boolean effectiveDepsDifference = !effectiveDependency.eq(newDependency);

        boolean difference = sqlDifference || depsDifference;

        boolean targetLatest = depsDifference || application.isRunning();

        boolean effectiveLatest = effectiveDepsDifference || application.isRunning();

        if (difference) {
            // 5) 检查是否存在latest版本的记录
            FlinkSql latestFlinkSql = flinkSqlService.getLatest(application.getId());
            boolean latestSqlDifference = false;
            boolean latestDepsDifference = false;
            if (latestFlinkSql != null) {
                latestFlinkSql.decode();
                latestSqlDifference = !latestFlinkSql.getSql().trim().equals(appParam.getFlinkSQL().trim());
                Application.Dependency latestDependency = Application.Dependency.jsonToDependency(latestFlinkSql.getDependency());
                latestDepsDifference = !latestDependency.eq(newDependency);
            }

            //不存在latest版本的记录
            if (latestFlinkSql == null) {
                FlinkSql sql = new FlinkSql(appParam);
                flinkSqlService.create(sql, targetLatest);
            } else {
                //和latest不相同.
                if (latestSqlDifference || latestDepsDifference) {
                    FlinkSql sql = new FlinkSql(appParam);
                    flinkSqlService.create(sql, effectiveLatest);
                }
            }
        } else if (versionChanged) {
            flinkSqlService.setLatestOrEffective(effectiveLatest, appParam.getSqlId(), appParam.getId());
        }

        // 6) 配置文件修改
        this.configService.update(appParam, application.isRunning());

        // 7) 判断 Effective的依赖和当前提交的是否发生变化
        if (effectiveDepsDifference) {
            application.setDeploy(DeployState.NEED_DEPLOY_AFTER_DEPENDENCY_UPDATE.get());
            this.configService.update(appParam, true);
        } else if (sqlDifference) {
            application.setDeploy(DeployState.NEED_RESTART_AFTER_SQL_UPDATE.get());
            this.configService.update(appParam, targetLatest);
        }
    }


    @Override
    public void deploy(Application appParam) {
        //executorService.submit(() -> {
            Application application = getById(appParam.getId());
            try {
                FlinkTrackingTask.refreshTracking(application.getId(), () -> {
                    application.setBackUpDescription(appParam.getBackUpDescription());
                    // 1) 需要重启的先停止服务
                    if (appParam.getRestart()) {
                        this.cancel(appParam);
                    } else if (!application.isRunning()) {
                        // 不需要重启的并且未正在运行的,则更改状态为发布中....
                        baseMapper.update(application, new UpdateWrapper<Application>()
                                .lambda()
                                .eq(Application::getId, application.getId())
                                .set(Application::getState, FlinkAppState.DEPLOYING.getValue())
                                .set(Application::getOptionState, OptionState.DEPLOYING.getValue())
                        );
                    }

                    // 2) backup
                    if (appParam.getBackUp()) {
                        this.backUpService.backup(application);
                    }

                    // 3) deploying...
                    File appHome = application.getAppHome();
                    HdfsUtils.delete(appHome.getPath());

                    try {
                        if (application.isCustomCodeJob()) {
                            log.info("CustomCodeJob deploying...");
                            File localJobHome = new File(application.getLocalAppBase(), application.getModule());
                            HdfsUtils.upload(localJobHome.getAbsolutePath(), workspace, false, true);
                            HdfsUtils.movie(workspace.concat("/").concat(application.getModule()), appHome.getPath());
                        } else {
                            log.info("FlinkSqlJob deploying...");
                            FlinkSql flinkSql = flinkSqlService.getLatest(application.getId());
                            application.setDependency(flinkSql.getDependency());
                            downloadDependency(application);
                        }
                        // 4) 更新发布状态,需要重启的应用则重新启动...
                        LambdaUpdateWrapper<Application> updateWrapper = new LambdaUpdateWrapper<>();
                        updateWrapper.eq(Application::getId, application.getId());
                        if (appParam.getRestart()) {
                            // 重新启动.
                            start(appParam);
                            // 将"需要重新发布"状态清空...
                            updateWrapper.set(Application::getDeploy, DeployState.NONE.get());
                        } else {
                            //正在运行的任务...
                            if (application.isRunning()) {
                                updateWrapper.set(Application::getDeploy, DeployState.NEED_RESTART_AFTER_DEPLOY.get());
                            } else {
                                updateWrapper.set(Application::getOptionState, OptionState.NONE.getValue());
                                updateWrapper.set(Application::getDeploy, DeployState.NONE.get());
                                updateWrapper.set(Application::getState, FlinkAppState.DEPLOYED.getValue());
                            }
                        }
                        baseMapper.update(application, updateWrapper);

                        //如果当前任务未运行,则直接将"lastst"的设置为Effective
                        if (!application.isRunning()) {
                            toEffective(application);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return null;
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        //});
    }

    @SneakyThrows
    private void downloadDependency(Application application) {
        //1) init.
        File jobLocalHome = application.getLocalFlinkSqlBase();
        if (jobLocalHome.exists()) {
            if (!CommonUtil.deleteFile(jobLocalHome)) {
                throw new RuntimeException(jobLocalHome + " delete failed.");
            }
        }

        File lib = new File(jobLocalHome, "lib");
        if (!lib.mkdirs()) {
            throw new RuntimeException(lib.getAbsolutePath() + " mkdirs failed.");
        }

        //2) maven pom...
        List<Application.Pom> pom = application.getDependencyObject().getPom();
        if (Utils.notEmpty(pom)) {
            log.info("downloadDependency..{}", pom.toString());
            StringBuilder builder = new StringBuilder();
            pom.forEach(x -> {
                String info = String.format("%s:%s:%s,", x.getGroupId(), x.getArtifactId(), x.getVersion());
                builder.append(info);
            });
            String packages = builder.deleteCharAt(builder.length() - 1).toString();
            /*
             * 默认去除以下依赖...
             */
            builder.setLength(0);
            builder.append("org.apache.flink:force-shading,")
                    .append("com.google.code.findbugs:jsr305,")
                    .append("org.slf4j:*,")
                    .append("org.apache.logging.log4j:*,");
            /*
             * 用户指定需要排除的依赖.
             */
            pom.stream().filter(x -> x.getExclusions() != null).forEach(x -> x.getExclusions().forEach(e -> {
                String info = String.format("%s:%s,", e.getGroupId(), e.getArtifactId());
                builder.append(info);
            }));
            String exclusions = builder.deleteCharAt(builder.length() - 1).toString();

            Long id = application.getId();
            StringBuilder logBuilder = this.tailBuffer.getOrDefault(id, new StringBuilder());
            Collection<String> dependencyJars = JavaConversions.asJavaCollection(
                    DependencyUtils.resolveMavenDependencies(
                            exclusions,
                            packages,
                            null,
                            null,
                            null,
                            out -> {
                                if (tailOutMap.containsKey(id)) {
                                    if (tailBeginning.containsKey(id)) {
                                        tailBeginning.remove(id);
                                        Arrays.stream(logBuilder.toString().split("\n"))
                                                .forEach(x -> simpMessageSendingOperations.convertAndSend("/resp/mvn", x));
                                    } else {
                                        simpMessageSendingOperations.convertAndSend("/resp/mvn", out);
                                    }
                                }
                                logBuilder.append(out).append("\n");
                            }
                    )
            );

            tailOutMap.remove(id);
            tailBeginning.remove(id);
            tailBuffer.remove(id);

            for (String x : dependencyJars) {
                File jar = new File(x);
                FileUtils.copyFileToDirectory(jar, lib);
            }
        }

        //3) upload jar by pomJar
        HdfsUtils.delete(application.getAppHome().getAbsolutePath());

        HdfsUtils.upload(jobLocalHome.getAbsolutePath(), workspace, false, true);

        //4) upload jar by uploadJar
        List<String> jars = application.getDependencyObject().getJar();
        if (Utils.notEmpty(jars)) {
            jars.forEach(jar -> {
                String src = APP_UPLOADS.concat("/").concat(jar);
                HdfsUtils.copyHdfs(src, application.getAppHome().getAbsolutePath().concat("/lib"), false, true);
            });
        }

    }

    @Override
    @RefreshCache
    public void clean(Application appParam) {
        appParam.setDeploy(DeployState.NONE.get());
        this.baseMapper.updateDeploy(appParam);
    }

    @Override
    public String readConf(Application appParam) throws IOException {
        File file = new File(appParam.getConfig());
        String conf = FileUtils.readFileToString(file, "utf-8");
        return Base64.getEncoder().encodeToString(conf.getBytes());
    }

    @Override
    @RefreshCache
    public Application getApp(Application appParam) {
        Application application = this.baseMapper.getApp(appParam);
        ApplicationConfig config = configService.getEffective(appParam.getId());
        if (config != null) {
            config.setToApplication(application);
        }
        if (application.isFlinkSqlJob()) {
            FlinkSql flinkSQL = flinkSqlService.getEffective(application.getId(), true);
            flinkSQL.setToApplication(application);
        } else {
            String path = this.projectService.getAppConfPath(application.getProjectId(), application.getModule());
            application.setConfPath(path);
        }
        return application;
    }

    @Override
    public String getMain(Application application) {
        Project project = new Project();
        project.setId(application.getProjectId());
        String modulePath = project.getAppBase().getAbsolutePath().concat("/").concat(application.getModule());
        File jarFile = new File(modulePath, application.getJar());
        Manifest manifest = Utils.getJarManifest(jarFile);
        return manifest.getMainAttributes().getValue("Main-Class");
    }

    @Override
    @RefreshCache
    public boolean mapping(Application appParam) {
        boolean mapping = this.baseMapper.mapping(appParam);
        Application application = getById(appParam.getId());
        FlinkTrackingTask.addTracking(application);
        return mapping;
    }

    @Override
    @RefreshCache
    public void cancel(Application appParam) {
        FlinkTrackingTask.setOptionState(appParam.getId(), OptionState.CANCELLING);
        Application application = getById(appParam.getId());
        application.setState(FlinkAppState.CANCELLING.getValue());
        if (appParam.getSavePointed()) {
            // 正在执行savepoint...
            FlinkTrackingTask.addSavepoint(application.getId());
            application.setOptionState(OptionState.SAVEPOINTING.getValue());
        } else {
            application.setOptionState(OptionState.CANCELLING.getValue());
        }
        this.baseMapper.updateById(application);
        //此步骤可能会比较耗时,重新开启一个线程去执行
        executorService.submit(() -> {
            try {
                String savePointDir = FlinkSubmit.stop(
                        ExecutionMode.of(application.getExecutionMode()),
                        application.getAppId(),
                        application.getJobId(),
                        appParam.getSavePointed(),
                        appParam.getDrain()
                );
                if (appParam.getSavePointed() && savePointDir != null) {
                    SavePoint savePoint = new SavePoint();
                    savePoint.setSavePoint(savePointDir);
                    savePoint.setAppId(application.getId());
                    savePoint.setLastest(true);
                    savePoint.setCreateTime(new Date());
                    // 之前的配置设置为已过期
                    savePointService.obsolete(application.getId());
                    savePointService.save(savePoint);
                }
            } catch (Exception e) {
                // 保持savepoint失败.则将之前的统统设置为过期
                if (appParam.getSavePointed()) {
                    savePointService.obsolete(application.getId());
                }
            }
        });
    }

    @Override
    public void updateTracking(Application appParam) {
        this.baseMapper.updateTracking(appParam);
    }


    /**
     * 设置任务正在启动中.(for webUI "state" display)
     *
     * @param appParam
     */
    @Override
    public void starting(Application appParam) {
        Application application = getById(appParam.getId());
        assert application != null;
        application.setState(FlinkAppState.STARTING.getValue());
        updateById(application);
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    @RefreshCache
    public boolean start(Application appParam) throws Exception {
        final Application application = getById(appParam.getId());
        assert application != null;
        //1) 真正执行启动相关的操作..
        String workspace = HdfsUtils.getDefaultFS().concat(ConfigConst.APP_WORKSPACE());

        String appConf, flinkUserJar;

        //2) 将lastst的设置为Effective的,(此时才真正变成当前生效的)
        this.toEffective(application);

        //获取一个最新的Effective的配置
        ApplicationConfig applicationConfig = configService.getEffective(application.getId());
        ExecutionMode executionMode = ExecutionMode.of(application.getExecutionMode());

        if (application.isCustomCodeJob()) {
            if (executionMode.equals(ExecutionMode.APPLICATION)) {
                switch (application.getApplicationType()) {
                    case STREAMX_FLINK:
                        String format = applicationConfig.getFormat() == 1 ? "yaml" : "prop";
                        appConf = String.format("%s://%s", format, applicationConfig.getContent());
                        String classPath = String.format("%s/%s/lib", workspace, application.getId());
                        flinkUserJar = String.format("%s/%s.jar", classPath, application.getModule());
                        break;
                    case APACHE_FLINK:
                        appConf = String.format("json://{\"%s\":\"%s\"}", ApplicationConfiguration.APPLICATION_MAIN_CLASS.key(), application.getMainClass());
                        classPath = String.format("%s/%s", workspace, application.getId());
                        flinkUserJar = String.format("%s/%s", classPath, application.getJar());
                        break;
                    default:
                        throw new IllegalArgumentException("[StreamX] ApplicationType must be (StreamX flink | Apache flink)... ");
                }
            } else if (executionMode.equals(ExecutionMode.YARN_PRE_JOB)) {
                switch (application.getApplicationType()) {
                    case STREAMX_FLINK:
                        String format = applicationConfig.getFormat() == 1 ? "yaml" : "prop";
                        appConf = String.format("%s://%s", format, applicationConfig.getContent());
                        File libPath = new File(application.getLocalAppBase() + "/" + application.getModule() + "/lib");
                        flinkUserJar = new File(libPath, application.getModule().concat(".jar")).getAbsolutePath();
                        break;
                    case APACHE_FLINK:
                        appConf = String.format("json://{\"%s\":\"%s\"}", ApplicationConfiguration.APPLICATION_MAIN_CLASS.key(), application.getMainClass());
                        libPath = new File(application.getLocalAppBase(), application.getModule());
                        flinkUserJar = new File(libPath, application.getModule().concat(".jar")).getAbsolutePath();
                        break;
                    default:
                        throw new IllegalArgumentException("[StreamX] ApplicationType must be (StreamX flink | Apache flink)... ");
                }
            } else {
                throw new UnsupportedOperationException("Unsupported..." + executionMode);
            }
        } else if (application.isFlinkSqlJob()) {
            //1) dist_userJar
            File localPlugins = new File(WebUtil.getAppDir("plugins"));
            assert localPlugins.exists();
            List<String> jars = Arrays.stream(Objects.requireNonNull(localPlugins.list())).filter(x -> x.matches("streamx-flink-sqlcli-.*\\.jar")).collect(Collectors.toList());
            if (jars.isEmpty()) {
                throw new IllegalArgumentException("[StreamX] can no found streamx-flink-sqlcli jar in " + localPlugins);
            }
            if (jars.size() > 1) {
                throw new IllegalArgumentException("[StreamX] found multiple streamx-flink-sqlcli jar in " + localPlugins);
            }
            String sqlDistJar = jars.get(0);
            //2) appConfig
            appConf = applicationConfig == null ? null : String.format("yaml://%s", applicationConfig.getContent());
            if (executionMode.equals(ExecutionMode.APPLICATION)) {
                //3) plugin
                String pluginPath = HdfsUtils.getDefaultFS().concat(ConfigConst.APP_PLUGINS());
                flinkUserJar = String.format("%s/%s", pluginPath, sqlDistJar);
            } else if (executionMode.equals(ExecutionMode.YARN_PRE_JOB)) {
                flinkUserJar = sqlDistJar;
            } else {
                throw new UnsupportedOperationException("Unsupported..." + executionMode);
            }
        } else {
            throw new UnsupportedOperationException("Unsupported...");
        }

        String savePointDir = null;
        if (appParam.getSavePointed()) {
            if (appParam.getSavePoint() == null) {
                SavePoint savePoint = savePointService.getLastest(appParam.getId());
                if (savePoint != null) {
                    savePointDir = savePoint.getSavePoint();
                }
            } else {
                savePointDir = appParam.getSavePoint();
            }
        }

        StringBuilder option = new StringBuilder();
        if (appParam.getAllowNonRestored()) {
            option.append(" -n ");
        }

        String[] dynamicOption = CommonUtil.notEmpty(application.getDynamicOptions())
                ? application.getDynamicOptions().split("\\s+")
                : new String[0];

        Map<String, Serializable> flameGraph = null;
        if (appParam.getFlameGraph()) {
            flameGraph = new HashMap<>(8);
            flameGraph.put("reporter", "com.streamxhub.streamx.plugin.profiling.reporter.HttpReporter");
            flameGraph.put("type", ApplicationType.STREAMX_FLINK.getType());
            flameGraph.put("id", application.getId());
            flameGraph.put("url", settingService.getStreamXAddress().concat("/metrics/report"));
            flameGraph.put("token", Utils.uuid());
            flameGraph.put("sampleInterval", 1000 * 60 * 2);
            flameGraph.put("metricInterval", 1000 * 60 * 2);
        }

        Map<String, Object> optionMap = application.getOptionMap();
        if (application.isFlinkSqlJob()) {
            FlinkSql flinkSql = flinkSqlService.getEffective(application.getId(), false);
            optionMap.put(ConfigConst.KEY_FLINK_SQL(null), flinkSql.getSql());
            optionMap.put(ConfigConst.KEY_JOB_ID(), application.getId());
        }

        ResolveOrder resolveOrder = ResolveOrder.of(application.getResolveOrder());

        SubmitRequest submitInfo = new SubmitRequest(
                flinkUserJar,
                DevelopmentMode.of(application.getJobType()),
                ExecutionMode.of(application.getExecutionMode()),
                resolveOrder,
                application.getJobName(),
                appConf,
                application.getApplicationType().getName(),
                savePointDir,
                flameGraph,
                option.toString(),
                optionMap,
                dynamicOption,
                application.getArgs()
        );

        ApplicationLog log = new ApplicationLog();
        log.setAppId(application.getId());
        log.setStartTime(new Date());

        try {
            SubmitResponse submitResponse = FlinkSubmit.submit(submitInfo);
            if (submitResponse.configuration() != null) {
                String jmMemory = submitResponse.configuration().toMap().get(JobManagerOptions.TOTAL_PROCESS_MEMORY.key());
                if (jmMemory != null) {
                    application.setJmMemory(MemorySize.parse(jmMemory).getMebiBytes());
                }
                String tmMemory = submitResponse.configuration().toMap().get(TaskManagerOptions.TOTAL_PROCESS_MEMORY.key());
                if (tmMemory != null) {
                    application.setTmMemory(MemorySize.parse(tmMemory).getMebiBytes());
                }
            }
            application.setAppId(submitResponse.applicationId().toString());
            application.setFlameGraph(appParam.getFlameGraph());
            log.setYarnAppId(submitResponse.applicationId().toString());
            application.setEndTime(null);
            updateById(application);

            //2) 启动完成将任务加入到监控中...
            // 更改操作状态...
            FlinkTrackingTask.setOptionState(appParam.getId(), OptionState.STARTING);
            // 加入到跟踪监控中...
            FlinkTrackingTask.addTracking(application);

            log.setSuccess(true);
            applicationLogService.save(log);
            //将savepoint设置为过期
            savePointService.obsolete(application.getId());
            return true;
        } catch (Exception e) {
            String exception = ExceptionUtils.getStackTrace(e);
            log.setException(exception);
            log.setSuccess(false);
            applicationLogService.save(log);
            Application app = getById(appParam.getId());
            app.setState(FlinkAppState.FAILED.getValue());
            app.setOptionState(OptionState.NONE.getValue());
            updateById(app);
            FlinkTrackingTask.stopTracking(appParam.getId());
            return false;
        }
    }

}
