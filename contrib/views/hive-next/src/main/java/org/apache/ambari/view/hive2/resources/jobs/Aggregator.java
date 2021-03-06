/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.hive2.resources.jobs;

import org.apache.ambari.view.hive2.persistence.utils.FilteringStrategy;
import org.apache.ambari.view.hive2.persistence.utils.Indexed;
import org.apache.ambari.view.hive2.persistence.utils.ItemNotFound;
import org.apache.ambari.view.hive2.resources.IResourceManager;
import org.apache.ambari.view.hive2.resources.files.FileService;
import org.apache.ambari.view.hive2.resources.jobs.atsJobs.HiveQueryId;
import org.apache.ambari.view.hive2.resources.jobs.atsJobs.IATSParser;
import org.apache.ambari.view.hive2.resources.jobs.atsJobs.TezDagId;
import org.apache.ambari.view.hive2.resources.jobs.viewJobs.Job;
import org.apache.ambari.view.hive2.resources.jobs.viewJobs.JobImpl;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * View Jobs and ATS Jobs aggregator.
 * There are 4 options:
 * 1) ATS ExecuteJob without operationId
 *    *Meaning*: executed outside of HS2
 *    - ExecuteJob info only from ATS
 * 2) ATS ExecuteJob with operationId
 *    a) Hive View ExecuteJob with same operationId is not present
 *        *Meaning*: executed with HS2
 *      - ExecuteJob info only from ATS
 *    b) Hive View ExecuteJob with operationId is present (need to merge)
 *        *Meaning*: executed with HS2 through Hive View
 *      - ExecuteJob info merged from ATS and from Hive View DataStorage
 * 3) ExecuteJob present only in Hive View, ATS does not have it
 *   *Meaning*: executed through Hive View, but Hadoop ExecuteJob was not created
 *   it can happen if user executes query without aggregation, like just "select * from TABLE"
 *   - ExecuteJob info only from Hive View
 */
public class Aggregator {
  protected final static Logger LOG =
    LoggerFactory.getLogger(Aggregator.class);

  private final IATSParser ats;
  private IResourceManager<Job> viewJobResourceManager;

  public Aggregator(IResourceManager<Job> jobResourceManager,
                    IATSParser ats) {
    this.viewJobResourceManager = jobResourceManager;
    this.ats = ats;
  }

  public List<Job> readAll(String username) {
    Set<String> addedOperationIds = new HashSet<>();

    List<Job> allJobs = new LinkedList<>();
    for (HiveQueryId atsHiveQuery : ats.getHiveQueryIdsList(username)) {

      TezDagId atsTezDag = getTezDagFromHiveQueryId(atsHiveQuery);

      JobImpl atsJob;
      if (hasOperationId(atsHiveQuery)) {
        try {
          Job viewJob = getJobByOperationId(urlSafeBase64ToHexString(atsHiveQuery.operationId));
          saveJobInfoIfNeeded(atsHiveQuery, atsTezDag, viewJob);

          atsJob = mergeAtsJobWithViewJob(atsHiveQuery, atsTezDag, viewJob);
        } catch (ItemNotFound itemNotFound) {
          // Executed from HS2, but outside of Hive View
          atsJob = atsOnlyJob(atsHiveQuery, atsTezDag);
        }
      } else {
        atsJob = atsOnlyJob(atsHiveQuery, atsTezDag);
      }
      allJobs.add(atsJob);

      addedOperationIds.add(atsHiveQuery.operationId);
    }

    return allJobs;
  }

  public Job readATSJob(Job viewJob) throws ItemNotFound {

    if (viewJob.getStatus().equals(Job.JOB_STATE_INITIALIZED) || viewJob.getStatus().equals(Job.JOB_STATE_UNKNOWN))
      return viewJob;

    String hexGuid = viewJob.getGuid();


    HiveQueryId atsHiveQuery = ats.getHiveQueryIdByOperationId(hexGuid);

    TezDagId atsTezDag = getTezDagFromHiveQueryId(atsHiveQuery);

    saveJobInfoIfNeeded(atsHiveQuery, atsTezDag, viewJob);
    return mergeAtsJobWithViewJob(atsHiveQuery, atsTezDag, viewJob);
  }

  private TezDagId getTezDagFromHiveQueryId(HiveQueryId atsHiveQuery) {
    TezDagId atsTezDag;
    if (atsHiveQuery.version >= HiveQueryId.ATS_15_RESPONSE_VERSION) {
      atsTezDag = ats.getTezDAGByEntity(atsHiveQuery.entity);
    } else if (atsHiveQuery.dagNames != null && atsHiveQuery.dagNames.size() > 0) {
      String dagName = atsHiveQuery.dagNames.get(0);

      atsTezDag = ats.getTezDAGByName(dagName);
    } else {
      atsTezDag = new TezDagId();
    }
    return atsTezDag;
  }

  protected boolean hasOperationId(HiveQueryId atsHiveQuery) {
    return atsHiveQuery.operationId != null;
  }

  protected JobImpl mergeAtsJobWithViewJob(HiveQueryId atsHiveQuery, TezDagId atsTezDag, Job viewJob) {
    JobImpl atsJob;
    try {
      atsJob = new JobImpl(PropertyUtils.describe(viewJob));
    } catch (IllegalAccessException e) {
      LOG.error("Can't instantiate JobImpl", e);
      return null;
    } catch (InvocationTargetException e) {
      LOG.error("Can't instantiate JobImpl", e);
      return null;
    } catch (NoSuchMethodException e) {
      LOG.error("Can't instantiate JobImpl", e);
      return null;
    }
    fillAtsJobFields(atsJob, atsHiveQuery, atsTezDag);
    return atsJob;
  }

  protected void saveJobInfoIfNeeded(HiveQueryId hiveQueryId, TezDagId tezDagId, Job viewJob) throws ItemNotFound {
    if (viewJob.getDagName() == null || viewJob.getDagName().isEmpty()) {
      if (hiveQueryId.dagNames != null && hiveQueryId.dagNames.size() > 0) {
        viewJob.setDagName(hiveQueryId.dagNames.get(0));
        viewJobResourceManager.update(viewJob, viewJob.getId());
      }
    }
    if (tezDagId.status != null && (tezDagId.status.compareToIgnoreCase(Job.JOB_STATE_UNKNOWN) != 0) &&
      !viewJob.getStatus().equalsIgnoreCase(tezDagId.status)) {
      viewJob.setDagId(tezDagId.entity);
      viewJob.setApplicationId(tezDagId.applicationId);
      viewJobResourceManager.update(viewJob, viewJob.getId());
    }
  }

  protected JobImpl atsOnlyJob(HiveQueryId atsHiveQuery, TezDagId atsTezDag) {
    JobImpl atsJob = new JobImpl();
    atsJob.setId(atsHiveQuery.entity);
    fillAtsJobFields(atsJob, atsHiveQuery, atsTezDag);

    String query = atsHiveQuery.query;
    atsJob.setTitle(query.substring(0, (query.length() > 42) ? 42 : query.length()));

    atsJob.setQueryFile(FileService.JSON_PATH_FILE + atsHiveQuery.url + "#otherinfo.QUERY!queryText");
    return atsJob;
  }

  protected JobImpl fillAtsJobFields(JobImpl atsJob, HiveQueryId atsHiveQuery, TezDagId atsTezDag) {
    atsJob.setApplicationId(atsTezDag.applicationId);

    if (atsHiveQuery.dagNames != null && atsHiveQuery.dagNames.size() > 0)
      atsJob.setDagName(atsHiveQuery.dagNames.get(0));
    atsJob.setDagId(atsTezDag.entity);
    if (atsHiveQuery.starttime != 0)
      atsJob.setDateSubmitted(atsHiveQuery.starttime);
    atsJob.setDuration(atsHiveQuery.duration);
    return atsJob;
  }

  protected Job getJobByOperationId(final String opId) throws ItemNotFound {
    List<Job> operationHandles = viewJobResourceManager.readAll(new FilteringStrategy() {
      @Override
      public boolean isConform(Indexed item) {
        Job opHandle = (Job) item;
        return opHandle.getGuid().equals(opId);
      }

      @Override
      public String whereStatement() {
        return "guid='" + opId + "'";
      }
    });

    if (operationHandles.size() != 1)
      throw new ItemNotFound();

    return viewJobResourceManager.read(operationHandles.get(0).getId());
  }

  protected static String urlSafeBase64ToHexString(String urlsafeBase64) {
    byte[] decoded = Base64.decodeBase64(urlsafeBase64);

    StringBuilder sb = new StringBuilder();
    for (byte b : decoded) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

}
