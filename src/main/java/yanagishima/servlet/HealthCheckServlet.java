package yanagishima.servlet;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import yanagishima.repository.TinyOrm;

@Api(tags = "internal")
@RestController
@RequiredArgsConstructor
public class HealthCheckServlet {
    private final TinyOrm db;

    @GetMapping("healthCheck")
    public void get() {
        db.executeQuery("select 1");
    }
}
