package com.adyen.workshop.controllers.views;

import com.adyen.workshop.PreauthStore;
import com.adyen.workshop.TokenStore;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;


@Controller
public class ViewController {
    private final Logger log = LoggerFactory.getLogger(ViewController.class);

    private final ApplicationConfiguration applicationConfiguration;
    private final TokenStore tokenStore;
    private final PreauthStore preauthStore;

    public ViewController(ApplicationConfiguration applicationConfiguration, TokenStore tokenStore, PreauthStore preauthStore) {
        this.applicationConfiguration = applicationConfiguration;
        this.tokenStore = tokenStore;
        this.preauthStore = preauthStore;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/preview")
    public String preview(@RequestParam String type, Model model) {
        model.addAttribute("type", type);
        return "preview";
    }

    @GetMapping("/checkout")
    public String checkout(@RequestParam String type, Model model) {
        model.addAttribute("type", type);
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        return "checkout";
    }

    // Tokenization Module - Shows the stored token and lets you charge or cancel the subscription.
    @GetMapping("/subscription")
    public String subscription(Model model) {
        var tokenRecord = tokenStore.get("shopperReference");
        model.addAttribute("token", tokenRecord != null ? tokenRecord.token() : null);
        model.addAttribute("cancelled", tokenRecord != null && tokenRecord.cancelled());
        return "subscription";
    }

    // Preauthorisation Module - Shows the current preauthorisation and lets you modify/capture/cancel/refund.
    @GetMapping("/preauthorisation")
    public String preauthorisation(Model model) {
        model.addAttribute("preauth", preauthStore.get());
        return "preauthorisation";
    }

    @GetMapping("/result/{type}")
    public String result(@PathVariable String type, Model model) {
        model.addAttribute("type", type);
        return "result";
    }

    @GetMapping("/redirect")
    public String redirect(Model model) {
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        return "redirect";
    }
}
