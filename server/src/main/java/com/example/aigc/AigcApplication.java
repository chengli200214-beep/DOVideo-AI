package com.example.aigc;

import com.example.server.config.*;
import com.example.server.controller.*;
import com.example.server.film.*;
import com.example.server.generation.*;
import com.example.server.mapper.UserMapper;
import com.example.server.service.AuthService;
import com.example.server.storyboard.*;
import com.example.server.utils.MinioUtils;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Standalone creator app: analysis LLM/ASR, RocketMQ and Qdrant beans are not loaded. */
@Configuration(proxyBeanMethods=false)
@EnableAutoConfiguration(excludeName={"org.redisson.spring.starter.RedissonAutoConfigurationV2","org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"})
@MapperScan(basePackageClasses=UserMapper.class)
@Import({AuthService.class,AuthInterceptor.class,WebConfig.class,UserController.class,ApiExceptionHandler.class,MinioConfig.class,MinioUtils.class,
    GenerationProperties.class,GenerationRepository.class,GenerationService.class,GenerationWorker.class,GenerationSchedulingConfig.class,GenerationAssetService.class,
    MockGenerationProvider.class,SiliconFlowGenerationProvider.class,SeedanceGenerationProvider.class,MinioGenerationArtifactStore.class,GenerationController.class,GenerationInputController.class,
    StoryboardRepository.class,StoryboardService.class,TemplateStoryboardPlanner.class,StoryboardController.class,ShotGenerationService.class,ShotGenerationController.class,
    StoryboardModelProperties.class,StoryboardModelRepository.class,StoryboardModelService.class,DeepSeekStoryboardPlanner.class,StoryboardModelWorker.class,StoryboardModelController.class,
    FilmService.class,CompositionRepository.class,CompositionWorker.class,FfmpegCompositionRenderer.class,FilmController.class,QualityEvaluationService.class,EvaluationController.class})
public class AigcApplication {
    public static void main(String[] args) { new SpringApplicationBuilder(AigcApplication.class).profiles("aigc").run(args); }
}
