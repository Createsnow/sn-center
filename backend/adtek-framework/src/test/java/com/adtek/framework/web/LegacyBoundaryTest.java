package com.adtek.framework.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实MVC异常分派保留405/409，并确认审计等CHECK失败仍为500；不依赖原异常文本。 */
class LegacyBoundaryTest {
    @RestController static class Fixture {
        @GetMapping("/boundary") void get(@RequestParam(defaultValue="lock") String kind){
            if(kind.equals("unique"))throw new DuplicateKeyException("SENSITIVE_ROW");
            if(kind.equals("check"))throw new DataIntegrityViolationException("SENSITIVE_ROW");
            throw new CannotAcquireLockException("SENSITIVE_ROW");
        }
    }
    @Test void unsupportedMethodKeeps405()throws Exception{
        mvc().perform(post("/boundary")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value(1000000007));
    }
    @Test void lockAndUniqueConflictKeep409ButCheckConstraintIsNotRetryAdvice()throws Exception{
        var mvc=mvc();
        for(String kind:java.util.List.of("lock","unique"))mvc.perform(get("/boundary").param("kind",kind)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1000000008)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SENSITIVE_ROW"))));
        mvc.perform(get("/boundary").param("kind","check")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(1000000002));
    }
    private org.springframework.test.web.servlet.MockMvc mvc(){return MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new GlobalExceptionHandler()).build();}
}
