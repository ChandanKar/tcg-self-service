package com.tcgdigital.vmcontrol.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String getRoot() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String getLogin() {
        return "forward:/login.html";
    }

    @GetMapping({"/home", "/home/"})
    public String getHome() {
        return "forward:/index.html";
    }
}
