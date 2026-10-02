package com.example.server.controller;
import com.example.server.common.Result;
import com.example.server.film.*;
import com.example.server.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/generation/projects/{id}")
public class FilmController {
    private final FilmService service;
    public FilmController(FilmService service) { this.service=service; }
    @GetMapping("/films") public Result<FilmService.Overview> overview(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id) throws Exception { return Result.ok(service.overview(user,id)); }
    @PostMapping("/selections") public Result<FilmService.Selection> selection(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id,@RequestBody FilmService.SelectionInput input) throws Exception { return Result.ok(service.select(user,id,input)); }
    @PostMapping("/films") public ResponseEntity<Result<FilmService.Submitted>> submit(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody FilmService.SubmitInput input) throws Exception {
        var result=service.submit(user,id,key,input); return ResponseEntity.status(result.reused()?200:201).body(Result.ok(result));
    }
    @GetMapping("/films/{job}") public Result<FilmService.Detail> get(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id,@PathVariable String job) throws Exception { return Result.ok(service.get(user,id,job)); }
    @PostMapping("/films/{job}/retry") public Result<FilmService.View> retry(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id,@PathVariable String job) { return Result.ok(service.retry(user,id,job)); }
    @GetMapping("/films/{job}/artifact") public Result<String> artifact(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id,@PathVariable String job) { return Result.ok(service.artifact(user,id,job)); }
    @GetMapping("/films/{job}/download") public ResponseEntity<org.springframework.core.io.InputStreamResource> download(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id,@PathVariable String job) throws Exception {
        var file=service.download(user,id,job); return ResponseEntity.ok().header("Content-Type","video/mp4").header("Content-Disposition","attachment; filename=film.mp4").contentLength(file.size()).body(new org.springframework.core.io.InputStreamResource(file.stream()));
    }
}
