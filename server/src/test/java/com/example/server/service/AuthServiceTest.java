package com.example.server.service;

import com.example.server.dto.AuthRequest;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    @Test void caseVariantsShareFailuresAndSuccessfulLoginClearsTheCanonicalCounter() {
        var redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        var mapper=mock(UserMapper.class);
        var counters=new HashMap<String,String>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call->counters.get(call.getArgument(0)));
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(call->{
            RedisScript<Long> script=call.getArgument(0);
            assertTrue(script.getScriptAsString().contains("PEXPIRE"));
            assertTrue(script.getScriptAsString().contains("PTTL"));
            List<String> keys=call.getArgument(1);
            assertEquals(List.of("auth:login-failures:demouser"),keys);
            long count=Long.parseLong(counters.getOrDefault(keys.getFirst(),"0"))+1;
            counters.put(keys.getFirst(),Long.toString(count)); return count;
        });
        when(redis.delete(anyString())).thenAnswer(call->counters.remove(call.getArgument(0))!=null);
        var auth=new AuthService(redis,mapper);
        var account=new User(); account.setId(1L); account.setUsername("DemoUser"); account.setPassword(auth.hashPassword("correct-test-password")); account.setRole("USER");
        when(mapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(account);
        for(int attempt=0;attempt<8;attempt++) assertEquals(401,auth.login(new AuthRequest(attempt%2==0?"DemoUser":"DEMOUSER","wrong-password",null)).code());
        assertEquals(429,auth.login(new AuthRequest("demouser","correct-test-password",null)).code());
        assertEquals(8L,Long.parseLong(counters.get("auth:login-failures:demouser")));
        auth.clearLoginFailures("DEMouser");
        auth.recordLoginFailure("DemoUser");
        assertEquals(200,auth.login(new AuthRequest("demouser","correct-test-password",null)).code());
        assertTrue(auth.loginAttemptAllowed("DEMOUSER"));
        verify(values,never()).increment(anyString());
        verify(redis,never()).expire(anyString(),anyLong(),any(java.util.concurrent.TimeUnit.class));
    }

    private static StringRedisTemplate countingRedis(Map<String,String> counters) {
        var redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call->counters.get(call.getArgument(0)));
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(call->{
            List<String> keys=call.getArgument(1);
            long count=Long.parseLong(counters.getOrDefault(keys.getFirst(),"0"))+1;
            counters.put(keys.getFirst(),Long.toString(count)); return count;
        });
        when(redis.delete(anyString())).thenAnswer(call->counters.remove(call.getArgument(0))!=null);
        return redis;
    }

    @Test void oneAddressSprayingManyAccountsIsThrottledAndSuccessDoesNotResetIt() {
        var counters=new HashMap<String,String>();
        var mapper=mock(UserMapper.class);
        var auth=new AuthService(countingRedis(counters),mapper,3);
        var account=new User(); account.setId(1L); account.setUsername("realuser"); account.setPassword(auth.hashPassword("correct-test-password")); account.setRole("USER");
        when(mapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(null);
        for(int i=0;i<3;i++) assertEquals(401,auth.login(new AuthRequest("victim"+i,"Password123",null),"203.0.113.9").code());
        when(mapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(account);
        assertEquals(429,auth.login(new AuthRequest("realuser","correct-test-password",null),"203.0.113.9").code());
        assertEquals(200,auth.login(new AuthRequest("realuser","correct-test-password",null),"198.51.100.1").code());
        assertEquals("3",counters.get("auth:login-failures:ip:203.0.113.9"));
    }

    @Test void unknownUsernameStillPaysThePasswordHashingCost() {
        var mapper=mock(UserMapper.class);
        when(mapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(null);
        var auth=spy(new AuthService(countingRedis(new HashMap<>()),mapper,30));
        assertEquals(401,auth.login(new AuthRequest("nobody","Password123",null),"203.0.113.9").code());
        verify(auth).passwordMatches(eq("Password123"),startsWith("pbkdf2$"));
    }
}
