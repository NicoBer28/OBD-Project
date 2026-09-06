package com.obd.api.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private Map<String, User> userByEmail = new HashMap<>();
    private Map<UUID, User> userById = new HashMap<>();

    public Optional<User> findByEmail(String email){
        return Optional.ofNullable(userByEmail.get(email));
    }

    public Optional<User> findById(UUID id){
        return Optional.ofNullable(userById.get(id));
    }

    public void saveAndFlush(User user){
        if(userByEmail.containsKey(user.getUserEmail()))
            throw new DataIntegrityViolationException("Email already in use");
        UUID id;
        do {
            id = UUID.randomUUID();
        }while (userById.containsKey(id));
        user.setUserId(id);
        userById.put(id, user);
        userByEmail.put(user.getUserEmail(), user);
    }
}
