package com.vprok.forms.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The sign-in page (FORMS-12). Spring Security handles the POST to /login and the logout itself. */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
