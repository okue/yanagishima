package yanagishima.servlet;

import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Collections.nCopies;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import okhttp3.Response;
import yanagishima.annotation.DatasourceAuth;
import yanagishima.client.presto.PrestoClient;
import yanagishima.config.YanagishimaConfig;
import yanagishima.model.db.Query;
import yanagishima.repository.TinyOrm;

@RestController
@RequiredArgsConstructor
public class QueryServlet {
    private static final int LIMIT = 100;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final YanagishimaConfig config;
    private final TinyOrm db;

    @DatasourceAuth
    @PostMapping("query")
    public Object post(@RequestParam String datasource,
                       @RequestParam Optional<String> user,
                       @RequestParam Optional<String> password,
                       HttpServletRequest request) throws IOException {
        String coordinatorServer = config.getPrestoCoordinatorServerOrNull(datasource);
        if (coordinatorServer == null) {
            return List.of();
        }

        String userName = request.getHeader(config.getAuditHttpHeaderName());
        String originalJson;
        try (Response prestoResponse = new PrestoClient(coordinatorServer, userName, user, password).get()) {
            originalJson = prestoResponse.body().string();
            int code = prestoResponse.code();
            if (code != SC_OK) {
                return Map.of("code", code, "error", prestoResponse.message());
            }
        }

        List<Map> list = OBJECT_MAPPER.readValue(originalJson, List.class);
        List<Map> runningList = list.stream().filter(m -> m.get("state").equals("RUNNING")).collect(Collectors.toList());
        List<Map> notRunningList = list.stream().filter(m -> !m.get("state").equals("RUNNING")).collect(Collectors.toList());
        runningList.sort((a, b) -> String.class.cast(b.get("queryId")).compareTo(String.class.cast(a.get("queryId"))));
        notRunningList.sort((a, b) -> String.class.cast(b.get("queryId")).compareTo(String.class.cast(a.get("queryId"))));

        List<Map> limitedList = new ArrayList<>();
        limitedList.addAll(runningList);
        limitedList.addAll(notRunningList.subList(0, Math.min(LIMIT, list.size()) - runningList.size()));

        List<String> queryIds = limitedList.stream().map(query -> (String) query.get("queryId")).collect(Collectors.toList());

        String placeholder = join(", ", nCopies(queryIds.size(), "?"));
        List<Query> queries = db.searchBySQL(Query.class,
                                             format("SELECT engine, query_id, fetch_result_time_string, query_string "
                                                    + "FROM query "
                                                    + "WHERE engine='presto' and datasource=\'%s\' and query_id IN (%s)",
                                                    datasource, placeholder),
                                             new ArrayList<>(queryIds));

        List<String> existDbQueryIds = new ArrayList<>();
        for (Query query : queries) {
            existDbQueryIds.add(query.getQueryId());
        }
        for (Map query : limitedList) {
            String queryId = (String) query.get("queryId");
            query.put("existdb", existDbQueryIds.contains(queryId));
        }
        return limitedList;
    }
}
