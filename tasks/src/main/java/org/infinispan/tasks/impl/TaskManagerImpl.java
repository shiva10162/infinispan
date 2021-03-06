package org.infinispan.tasks.impl;

import org.infinispan.commons.util.CollectionFactory;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.security.Security;
import org.infinispan.tasks.Task;
import org.infinispan.tasks.TaskContext;
import org.infinispan.tasks.TaskExecution;
import org.infinispan.tasks.TaskManager;
import org.infinispan.tasks.logging.Log;
import org.infinispan.tasks.spi.TaskEngine;
import org.infinispan.util.TimeService;
import org.infinispan.util.logging.LogFactory;
import org.infinispan.util.logging.events.EventLogCategory;
import org.infinispan.util.logging.events.EventLogManager;
import org.infinispan.util.logging.events.EventLogger;

import javax.security.auth.Subject;
import java.lang.invoke.MethodHandles;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

import static org.infinispan.tasks.logging.Messages.MESSAGES;

/**
 * TaskManagerImpl.
 *
 * @author Tristan Tarrant
 * @since 8.1
 */
@Scope(Scopes.GLOBAL)
public class TaskManagerImpl implements TaskManager {
   private static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass(), Log.class);
   private EmbeddedCacheManager cacheManager;
   private List<TaskEngine> engines;
   private ConcurrentMap<UUID, TaskExecution> runningTasks;
   private TimeService timeService;
   private boolean useSecurity;

   public TaskManagerImpl() {
      engines = new ArrayList<>();
      runningTasks = CollectionFactory.makeConcurrentMap();
   }

   @Inject
   public void initialize(final EmbeddedCacheManager cacheManager, final TimeService timeService) {
      this.cacheManager = cacheManager;
      this.timeService = timeService;
      this.useSecurity = cacheManager.getCacheManagerConfiguration().security().authorization().enabled();
   }

   public synchronized void registerTaskEngine(TaskEngine engine) {
      if (engines.contains(engine)) {
         throw log.duplicateTaskEngineRegistration(engine.getName());
      } else {
         engines.add(engine);
      }
   }

   @Override
   public <T> CompletableFuture<T> runTask(String name, TaskContext context) {
      for(TaskEngine engine : engines) {
         if (engine.handles(name)) {
            Address address = cacheManager.getAddress();
            Optional<String> who;
            if (useSecurity) {
               Subject subject = Security.getSubject();
               Principal userPrincipal = Security.getSubjectUserPrincipal(subject);
               who = Optional.of(userPrincipal.getName());
            } else {
               who = Optional.empty();
            }
            TaskExecutionImpl exec = new TaskExecutionImpl(name, address == null ? "local" : address.toString(), who, context);
            exec.setStart(timeService.instant());
            runningTasks.put(exec.getUUID(), exec);
            CompletableFuture<T> task = engine.runTask(name, context);
            return task.whenComplete((r, e) -> {
               if (context.isLogEvent()) {
                  EventLogger eventLog = EventLogManager.getEventLogger(cacheManager).scope(cacheManager.getAddress());
                  who.ifPresent(s -> eventLog.who(s));
                  context.getCache().ifPresent(cache -> eventLog.context(cache));
                  if (e != null) {
                     eventLog.detail(e)
                           .error(EventLogCategory.TASKS, MESSAGES.taskFailure(name));
                  } else {
                     eventLog.detail(String.valueOf(r))
                           .info(EventLogCategory.TASKS, MESSAGES.taskSuccess(name));
                  }
               }
               runningTasks.remove(exec.getUUID());
            });
         }
      }
      throw log.unknownTask(name);
   }

   @Override
   public List<TaskExecution> getCurrentTasks() {
      return new ArrayList<>(runningTasks.values());
   }

   @Override
   public List<TaskEngine> getEngines() {
      return Collections.unmodifiableList(engines);
   }

   @Override
   public List<Task> getTasks() {
      List<Task> tasks = new ArrayList<>();
      engines.forEach(engine -> tasks.addAll(engine.getTasks()));
      return tasks;
   }

}
