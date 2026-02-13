package com.tcgdigital.vmcontrol.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String getRoot() {
        return "redirect:/home";
    }

    @GetMapping("/home")
    public String getHome() {
        return "forward:/home.html";
    }
}
