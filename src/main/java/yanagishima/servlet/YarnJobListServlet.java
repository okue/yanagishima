package yanagishima.servlet;

import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Collections.nCopies;
import static yanagishima.util.Constants.YANAGISHIAM_HIVE_JOB_PREFIX;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import yanagishima.annotation.DatasourceAuth;
import yanagishima.config.YanagishimaConfig;
import yanagishima.model.db.Query;
import yanagishima.repository.TinyOrm;
import yanagishima.util.YarnUtil;

@RestController
@RequiredArgsConstructor
public class YarnJobListServlet {
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final int LIMIT = 100;

	private final YanagishimaConfig config;
	private final TinyOrm db;

	@DatasourceAuth
	@GetMapping("yarnJobList")
	public void get(@RequestParam String datasource, HttpServletRequest request, HttpServletResponse response) throws IOException {
		String resourceManagerUrl = config.getResourceManagerUrl(datasource);
		List<Map> yarnJobs = YarnUtil.getJobList(resourceManagerUrl, config.getResourceManagerBegin(datasource));
		List<Map> runningJobs = yarnJobs.stream().filter(job -> job.get("state").equals("RUNNING")).collect(Collectors.toList());;
		List<Map> finishedJobs = yarnJobs.stream().filter(job -> !job.get("state").equals("RUNNING")).collect(Collectors.toList());;
		runningJobs.sort((a, b)-> ((String) b.get("id")).compareTo((String) a.get("id")));
		finishedJobs.sort((a, b)-> ((String) b.get("id")).compareTo((String) a.get("id")));

		List<Map> jobs;
		if (runningJobs.size() >= LIMIT) {
			jobs = runningJobs.subList(0, LIMIT);
		} else if (yarnJobs.size() > LIMIT) {
			jobs = new ArrayList<>();
			jobs.addAll(runningJobs);
			jobs.addAll(finishedJobs.subList(0, LIMIT - runningJobs.size()));
		} else {
			jobs = yarnJobs;
		}

		String userName = request.getHeader(config.getAuditHttpHeaderName());
		List<String> queryIds = toQueryIds(jobs, userName);
		List<String> existingQueryIds = getExistingQueries(queryIds, datasource);
		List<Map> jobsWitExistDb = toJobsWitExistDb(jobs, userName, existingQueryIds);

		response.setContentType(MediaType.APPLICATION_JSON);
		PrintWriter writer = response.getWriter();
		String json = OBJECT_MAPPER.writeValueAsString(jobsWitExistDb);
		writer.println(json);
	}

	private List<String> toQueryIds(List<Map> jobs, String userName) {
		List<String> queryIds = new ArrayList<>();
		for (Map job : jobs) {
			String name = (String) job.get("name");
			if (name.startsWith(YANAGISHIAM_HIVE_JOB_PREFIX)) {
				String queryId = jobNameToQueryId(name, userName);
				queryIds.add(queryId);
			}
		}
		return queryIds;
	}

	private List<String> getExistingQueries(List<String> queryIds, String datasource) {
		if (queryIds.isEmpty()) {
			return List.of();
		}

		String placeholder = join(", ", nCopies(queryIds.size(), "?"));
		List<Query> queries = db.searchBySQL(Query.class,
											 format("SELECT engine, query_id, fetch_result_time_string, query_string "
													+ "FROM query "
													+ "WHERE engine='hive' and datasource=\'%s\' and query_id IN (%s)",
													datasource, placeholder),
											 queryIds.stream().collect(Collectors.toList()));

		return queries.stream().map(Query::getQueryId).collect(Collectors.toList());
	}

	private List<Map> toJobsWitExistDb(List<Map> jobs, String userName, List<String> existingQueryIds) {
		List<Map> jobsWitExistDb = new ArrayList<>();
		for (Map job : jobs) {
			String name = (String) job.get("name");
			boolean existDb = false;
			if (name.startsWith(YANAGISHIAM_HIVE_JOB_PREFIX)) {
				String queryId = jobNameToQueryId(name, userName);
				existDb = existingQueryIds.contains(queryId);
			}
			job.put("existdb", existDb);
			jobsWitExistDb.add(job);
		}
		return jobsWitExistDb;
	}

	private static String jobNameToQueryId(String jobName, String userName) {
		if (userName == null) {
			return jobName.substring(YANAGISHIAM_HIVE_JOB_PREFIX.length());
		}
		return jobName.substring(YANAGISHIAM_HIVE_JOB_PREFIX.length() + userName.length() + 1);
	}
}

