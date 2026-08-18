package ru.mcs.controlplane.web;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.mcs.controlplane.domain.Tier;
import ru.mcs.controlplane.service.AccessCodeService;
import ru.mcs.controlplane.service.ApiKeyService;
import ru.mcs.controlplane.service.AuthService;
import ru.mcs.controlplane.service.UsageService;
import ru.mcs.controlplane.service.UserService;

@Controller
@RequiredArgsConstructor
public class PageController {

    private final AuthService authService;
    private final ApiKeyService apiKeyService;
    private final AccessCodeService accessCodeService;
    private final UserService userService;
    private final UsageService usageService;

    @GetMapping("/")
    public String index() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication authentication) {
        var user = userService.getCurrent(authentication);
        var usage = usageService.usage(user.getId(), user.getTier());
        model.addAttribute("email", user.getEmail());
        model.addAttribute("tier", user.getTier().name());
        model.addAttribute("admin", user.isAdmin());
        model.addAttribute("dailyUsed", usage.dailyUsed());
        model.addAttribute("dailyLimit", limitText(usage.dailyLimit()));
        model.addAttribute("monthlyUsed", usage.monthlyUsed());
        model.addAttribute("monthlyLimit", limitText(usage.monthlyLimit()));
        model.addAttribute("keys", apiKeyService.list(user.getId()));
        return "dashboard";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("codes", accessCodeService.listCodes());
        model.addAttribute("users", userService.list());
        return "admin";
    }

    @PostMapping("/register")
    public String register(@RequestParam String email, @RequestParam String password, RedirectAttributes ra) {
        try {
            authService.register(email, password);
            return "redirect:/login";
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("error", e.getReason());
            return "redirect:/register";
        }
    }

    @PostMapping("/keys")
    public String createKey(Authentication authentication, RedirectAttributes ra) {
        var user = userService.getCurrent(authentication);
        var created = apiKeyService.createKey(user.getId());
        ra.addFlashAttribute("newKey", created.plaintext());
        return "redirect:/dashboard";
    }

    @PostMapping("/keys/{id}/revoke")
    public String revokeKey(@PathVariable UUID id, Authentication authentication) {
        var user = userService.getCurrent(authentication);
        apiKeyService.revoke(user.getId(), id);
        return "redirect:/dashboard";
    }

    @PostMapping("/subscribe")
    public String subscribe(@RequestParam String code, Authentication authentication, RedirectAttributes ra) {
        var user = userService.getCurrent(authentication);
        try {
            accessCodeService.activate(user.getId(), code);
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("error", e.getReason());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/admin/codes")
    public String generateCodes(@RequestParam int count, @RequestParam String tier,
                                @RequestParam(required = false) String expiresAt) {
        var parsedExpires = (expiresAt == null || expiresAt.isBlank()) ? null : Instant.parse(expiresAt);
        accessCodeService.generate(count, Tier.valueOf(tier), parsedExpires);
        return "redirect:/admin";
    }

    @PostMapping("/admin/users/{id}/tier")
    public String changeTier(@PathVariable UUID id, @RequestParam String tier) {
        userService.changeTier(id, Tier.valueOf(tier));
        return "redirect:/admin";
    }

    private static String limitText(Long limit) {
        return limit == null ? "unlimited" : String.valueOf(limit);
    }
}
