package com.sunjin.CP_BOT.web;

import com.sunjin.CP_BOT.common.security.AuthenticatedRoles;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Thymeleaf 화면 라우팅. 인증/RBAC은 SecurityConfig가 이미 처리하므로 여기서는 화면에 필요한
 * 사용자 정보(이름, 관리자 여부)만 모델에 담아 넘긴다.
 */
@Controller
public class PageController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String chat(Model model, Authentication authentication) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("isAdmin", AuthenticatedRoles.extract(authentication).contains("ADMIN"));
        return "chat";
    }

    // ADMIN 제한은 SecurityConfig(/admin/**)가 이미 걸어주므로 여기서는 화면만 반환한다.
    @GetMapping("/admin/documents")
    public String documents(Model model, Authentication authentication) {
        model.addAttribute("username", authentication.getName());
        return "admin-documents";
    }
}
