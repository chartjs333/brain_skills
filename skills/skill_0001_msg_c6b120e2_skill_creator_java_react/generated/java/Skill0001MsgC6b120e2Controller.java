package generated.skills;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills/skill_0001_msg_c6b120e2_skill_creator_java_react")
public class Skill0001MsgC6b120e2Controller {
    private final Skill0001MsgC6b120e2Service service = new Skill0001MsgC6b120e2Service();

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody(required = false) Map<String, Object> input) {
        return service.execute(input);
    }
}
