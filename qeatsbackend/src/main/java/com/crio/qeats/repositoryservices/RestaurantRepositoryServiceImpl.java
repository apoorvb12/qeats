/*
 *
 *  * Copyright (c) Crio.Do 2019. All rights reserved
 *
 */

package com.crio.qeats.repositoryservices;

import ch.hsr.geohash.GeoHash;
import redis.clients.jedis.Jedis;
import com.crio.qeats.configs.RedisConfiguration;
import com.crio.qeats.dto.Restaurant;
import com.crio.qeats.globals.GlobalConstants;
import com.crio.qeats.models.ItemEntity;
import com.crio.qeats.models.MenuEntity;
import com.crio.qeats.models.RestaurantEntity;
import com.crio.qeats.repositories.ItemRepository;
import com.crio.qeats.repositories.MenuRepository;
import com.crio.qeats.repositories.RestaurantRepository;
import com.crio.qeats.utils.GeoLocation;
import com.crio.qeats.utils.GeoUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Provider;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;

@Primary
@Service
public class RestaurantRepositoryServiceImpl implements RestaurantRepositoryService {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private Provider<ModelMapper> modelMapperProvider;

  @Autowired
  private RestaurantRepository restaurantRepository;

  @Autowired
  private RedisConfiguration redisConfiguration;


  private boolean isOpenNow(LocalTime time, RestaurantEntity res) {
    LocalTime openingTime = LocalTime.parse(res.getOpensAt());
    LocalTime closingTime = LocalTime.parse(res.getClosesAt());

    return time.isAfter(openingTime) && time.isBefore(closingTime);
  }

  // TODO: CRIO_TASK_MODULE_NOSQL
  // Objectives:
  // 1. Implement findAllRestaurantsCloseby.
  // 2. Remember to keep the precision of GeoHash in mind while using it as a key.
  // Check RestaurantRepositoryService.java file for the interface contract.
  public List<Restaurant> findAllRestaurantsCloseBy(Double latitude,
      Double longitude, LocalTime currentTime, Double servingRadiusInKms) {

        List<Restaurant> restaurants = null;
        if (redisConfiguration.isCacheAvailable()) {
          restaurants = findAllRestaurantsCloseByFromCache(latitude, longitude, currentTime, servingRadiusInKms);
        } else {
          restaurants = findAllRestaurantsCloseFromDb(latitude, longitude, currentTime, servingRadiusInKms);
        }
        return restaurants;
      }


  // TODO: CRIO_TASK_MODULE_NOSQL
  // Objective:
  // 1. Check if a restaurant is nearby and open. If so, it is a candidate to be returned.
  // NOTE: How far exactly is "nearby"?

  /**
   * Utility method to check if a restaurant is within the serving radius at a given time.
   * @return boolean True if restaurant falls within serving radius and is open, false otherwise
   */
  private boolean isRestaurantCloseByAndOpen(RestaurantEntity restaurantEntity,
      LocalTime currentTime, Double latitude, Double longitude, Double servingRadiusInKms) {
    if (isOpenNow(currentTime, restaurantEntity)) {
      return GeoUtils.findDistanceInKm(latitude, longitude,
          restaurantEntity.getLatitude(), restaurantEntity.getLongitude())
          < servingRadiusInKms;
    }

    return false;
  }

  private List<Restaurant> findAllRestaurantsCloseByFromCache(Double latitude, Double longitude, LocalTime currentTime,
  Double servingRadiusInKms) {
    List<Restaurant> restaurantList = new ArrayList<>();
    GeoLocation geoLocation = new GeoLocation(latitude, longitude);
    GeoHash geoHash = GeoHash.withCharacterPrecision(geoLocation.getLatitude(), geoLocation.getLongitude(), 7);
    try (Jedis jedis = redisConfiguration.getJedisPool().getResource()) {
    String jsonStringFromCache = jedis.get(geoHash.toBase32());

  if (jsonStringFromCache == null) {
    // Cache needs to be updated.
    String createdJsonString = "";
    try {
      restaurantList = findAllRestaurantsCloseFromDb(geoLocation.getLatitude(), geoLocation.getLongitude(),
          currentTime, servingRadiusInKms);
      createdJsonString = new ObjectMapper().writeValueAsString(restaurantList);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }

    // Do operations with jedis resource
    jedis.setex(geoHash.toBase32(), GlobalConstants.REDIS_ENTRY_EXPIRY_IN_SECONDS, createdJsonString);
  } else {
    try {
      restaurantList = new ObjectMapper().readValue(jsonStringFromCache, new TypeReference<List<Restaurant>>() {
      });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
return restaurantList;
}


private List<Restaurant> findAllRestaurantsCloseFromDb(Double latitude, Double longitude, LocalTime currentTime,
      Double servingRadiusInKms) {
    ModelMapper modelMapper = modelMapperProvider.get();
    List<RestaurantEntity> restaurantEntities = restaurantRepository.findAll();
    List<Restaurant> restaurants = new ArrayList<Restaurant>();
    for (RestaurantEntity restaurantEntity : restaurantEntities) {
      if (isRestaurantCloseByAndOpen(restaurantEntity, currentTime, latitude, longitude, servingRadiusInKms)) {
        restaurants.add(modelMapper.map(restaurantEntity, Restaurant.class));
      }
    }
    return restaurants;
  }

  @Override
  public List<Restaurant> findRestaurantsByName(Double latitude, Double longitude,
            String searchString, LocalTime currentTime, Double servingRadiusInKms) {
    BasicQuery query = new BasicQuery("{name: {$regex: /" + searchString + "/i}}");
    List<RestaurantEntity> restaurants = mongoTemplate
        .find(query, RestaurantEntity.class, "restaurants");
    List<Restaurant> restaurantList = new ArrayList<Restaurant>();

    for (RestaurantEntity restaurant : restaurants) {
      if (isRestaurantCloseByAndOpen(restaurant, currentTime,
          latitude, longitude, servingRadiusInKms)) {
            Restaurant r = new Restaurant();
            r.setRestaurantId(restaurant.getRestaurantId());
            r.setName(restaurant.getName());
            r.setCity(restaurant.getCity());
            r.setImageUrl(restaurant.getImageUrl());
            r.setLatitude(restaurant.getLatitude());
            r.setLongitude(restaurant.getLongitude());
            r.setOpensAt(restaurant.getOpensAt());
            r.setClosesAt(restaurant.getClosesAt());
            r.setAttributes(restaurant.getAttributes());
            restaurantList.add(r);
          }
    }
    return restaurantList;
  }

@Override
public List<Restaurant> findRestaurantsByAttributes(Double latitude, Double longitude,
    String searchString, LocalTime currentTime, Double servingRadiusInKms) {
  // TODO Auto-generated method stub
  BasicQuery query = new BasicQuery("{attributes: {$regex: /" + searchString + "/i}}");
  List<RestaurantEntity> restaurants = mongoTemplate.find(query, RestaurantEntity.class, "restaurants");
  List<Restaurant> restaurantList = new ArrayList<Restaurant>();
  for (RestaurantEntity restaurant : restaurants) {
    if (isRestaurantCloseByAndOpen(restaurant, currentTime,latitude, longitude, servingRadiusInKms)) {
      Restaurant r = new Restaurant();
      r.setRestaurantId(restaurant.getRestaurantId());
      r.setName(restaurant.getName());
      r.setCity(restaurant.getCity());
      r.setImageUrl(restaurant.getImageUrl());
      r.setLatitude(restaurant.getLatitude());
      r.setLongitude(restaurant.getLongitude());
      r.setOpensAt(restaurant.getOpensAt());
      r.setClosesAt(restaurant.getClosesAt());
      r.setAttributes(restaurant.getAttributes());
      restaurantList.add(r);
    }
  }  
  return restaurantList;
}

@Override
public List<Restaurant> findRestaurantsByItemName(Double latitude, Double longitude,
    String searchString, LocalTime currentTime, Double servingRadiusInKms) {
  // TODO Auto-generated method stub
  BasicQuery query = new BasicQuery("{'items.name': {$regex: /" + searchString + "/i}}");
  List<MenuEntity> menus = mongoTemplate.find(query, MenuEntity.class, "menus");
  List<RestaurantEntity> restaurants = new ArrayList<>();
  for (MenuEntity menu : menus) {
    String restaurantId = menu.getRestaurantId();
    BasicQuery restaurantQuery = new BasicQuery("{restaurantId:" + restaurantId + "}");
    restaurants.add(mongoTemplate
        .findOne(restaurantQuery, RestaurantEntity.class, "restaurants"));
  }
  List<Restaurant> restaurantList = new ArrayList<Restaurant>();

  for (RestaurantEntity restaurant : restaurants) {
    if (isRestaurantCloseByAndOpen(restaurant, currentTime,latitude, longitude, servingRadiusInKms)) {
      Restaurant r = new Restaurant();
      r.setRestaurantId(restaurant.getRestaurantId());
      r.setName(restaurant.getName());
      r.setCity(restaurant.getCity());
      r.setImageUrl(restaurant.getImageUrl());
      r.setLatitude(restaurant.getLatitude());
      r.setLongitude(restaurant.getLongitude());
      r.setOpensAt(restaurant.getOpensAt());
      r.setClosesAt(restaurant.getClosesAt());
      r.setAttributes(restaurant.getAttributes());
      restaurantList.add(r);    
    }
  }
  return restaurantList;
}

@Override
public List<Restaurant> findRestaurantsByItemAttributes(Double latitude, Double longitude,
    String searchString, LocalTime currentTime, Double servingRadiusInKms) {
    BasicQuery query = new BasicQuery("{'items.attributes': {$regex: /" + searchString + "/i}}");
    List<MenuEntity> menus = mongoTemplate.find(query, MenuEntity.class, "menus");
    List<RestaurantEntity> restaurants = new ArrayList<>();
    for (MenuEntity menu : menus) {
      String restaurantId = menu.getRestaurantId();
      BasicQuery restaurantQuery = new BasicQuery("{restaurantId:" + restaurantId + "}");
      restaurants.add(mongoTemplate.findOne(restaurantQuery, RestaurantEntity.class, "restaurants"));
    }
    List<Restaurant> restaurantList = new ArrayList<Restaurant>();
      for (RestaurantEntity restaurant : restaurants) {
        if (isRestaurantCloseByAndOpen(restaurant, currentTime,latitude, longitude, servingRadiusInKms)) {
          Restaurant r = new Restaurant();
          r.setRestaurantId(restaurant.getRestaurantId());
          r.setName(restaurant.getName());
          r.setCity(restaurant.getCity());
          r.setImageUrl(restaurant.getImageUrl());
          r.setLatitude(restaurant.getLatitude());
          r.setLongitude(restaurant.getLongitude());
          r.setOpensAt(restaurant.getOpensAt());
          r.setClosesAt(restaurant.getClosesAt());
          r.setAttributes(restaurant.getAttributes());
          restaurantList.add(r);    
        }
      }
      return restaurantList;
    }

    @Override
    @Async
    public CompletableFuture<List<Restaurant>> findRestaurantsByNameAsync(Double latitude, Double longitude,
              String searchString, LocalTime currentTime, Double servingRadiusInKms) {
      BasicQuery query = new BasicQuery("{name: {$regex: /" + searchString + "/i}}");
      List<RestaurantEntity> restaurants = mongoTemplate
          .find(query, RestaurantEntity.class, "restaurants");
      List<Restaurant> restaurantList = new ArrayList<Restaurant>();
  
      for (RestaurantEntity restaurant : restaurants) {
        if (isRestaurantCloseByAndOpen(restaurant, currentTime,
            latitude, longitude, servingRadiusInKms)) {
              Restaurant r = new Restaurant();
              r.setRestaurantId(restaurant.getRestaurantId());
              r.setName(restaurant.getName());
              r.setCity(restaurant.getCity());
              r.setImageUrl(restaurant.getImageUrl());
              r.setLatitude(restaurant.getLatitude());
              r.setLongitude(restaurant.getLongitude());
              r.setOpensAt(restaurant.getOpensAt());
              r.setClosesAt(restaurant.getClosesAt());
              r.setAttributes(restaurant.getAttributes());
              restaurantList.add(r);
            }
      }
      return CompletableFuture.completedFuture(restaurantList);
    }
  
    @Override
    @Async
    public CompletableFuture<List<Restaurant>> findRestaurantsByAttributesAsync(Double latitude, Double longitude,
        String searchString, LocalTime currentTime, Double servingRadiusInKms) {
      // TODO Auto-generated method stub
      BasicQuery query = new BasicQuery("{attributes: {$regex: /" + searchString + "/i}}");
      List<RestaurantEntity> restaurants = mongoTemplate.find(query, RestaurantEntity.class, "restaurants");
      List<Restaurant> restaurantList = new ArrayList<Restaurant>();
      for (RestaurantEntity restaurant : restaurants) {
        if (isRestaurantCloseByAndOpen(restaurant, currentTime,latitude, longitude, servingRadiusInKms)) {
          Restaurant r = new Restaurant();
          r.setRestaurantId(restaurant.getRestaurantId());
          r.setName(restaurant.getName());
          r.setCity(restaurant.getCity());
          r.setImageUrl(restaurant.getImageUrl());
          r.setLatitude(restaurant.getLatitude());
          r.setLongitude(restaurant.getLongitude());
          r.setOpensAt(restaurant.getOpensAt());
          r.setClosesAt(restaurant.getClosesAt());
          r.setAttributes(restaurant.getAttributes());
          restaurantList.add(r);
        }
      }  
      return CompletableFuture.completedFuture(restaurantList);
    }

    @Override
    @Async
    public CompletableFuture<List<Restaurant>> findRestaurantsByItemNameAsync(Double latitude, Double longitude,
        String searchString, LocalTime currentTime, Double servingRadiusInKms) {
      // TODO Auto-generated method stub
      BasicQuery query = new BasicQuery("{'items.name': {$regex: /" + searchString + "/i}}");
      List<MenuEntity> menus = mongoTemplate.find(query, MenuEntity.class, "menus");
      List<RestaurantEntity> restaurants = new ArrayList<>();
      for (MenuEntity menu : menus) {
        String restaurantId = menu.getRestaurantId();
        BasicQuery restaurantQuery = new BasicQuery("{restaurantId:" + restaurantId + "}");
        restaurants.add(mongoTemplate
            .findOne(restaurantQuery, RestaurantEntity.class, "restaurants"));
      }
      List<Restaurant> restaurantList = new ArrayList<Restaurant>();
    
      for (RestaurantEntity restaurant : restaurants) {
        if (isRestaurantCloseByAndOpen(restaurant, currentTime,latitude, longitude, servingRadiusInKms)) {
          Restaurant r = new Restaurant();
          r.setRestaurantId(restaurant.getRestaurantId());
          r.setName(restaurant.getName());
          r.setCity(restaurant.getCity());
          r.setImageUrl(restaurant.getImageUrl());
          r.setLatitude(restaurant.getLatitude());
          r.setLongitude(restaurant.getLongitude());
          r.setOpensAt(restaurant.getOpensAt());
          r.setClosesAt(restaurant.getClosesAt());
          r.setAttributes(restaurant.getAttributes());
          restaurantList.add(r);    
        }
      }
      return CompletableFuture.completedFuture(restaurantList);
    }
    
    @Override
    @Async
    public CompletableFuture<List<Restaurant>> findRestaurantsByItemAttributesAsync(Double latitude, Double longitude,
        String searchString, LocalTime currentTime, Double servingRadiusInKms) {
        BasicQuery query = new BasicQuery("{'items.attributes': {$regex: /" + searchString + "/i}}");
        List<MenuEntity> menus = mongoTemplate.find(query, MenuEntity.class, "menus");
        List<RestaurantEntity> restaurants = new ArrayList<>();
        for (MenuEntity menu : menus) {
          String restaurantId = menu.getRestaurantId();
          BasicQuery restaurantQuery = new BasicQuery("{restaurantId:" + restaurantId + "}");
          restaurants.add(mongoTemplate.findOne(restaurantQuery, RestaurantEntity.class, "restaurants"));
        }
        List<Restaurant> restaurantList = new ArrayList<Restaurant>();
          for (RestaurantEntity restaurant : restaurants) {
            if (isRestaurantCloseByAndOpen(restaurant, currentTime,latitude, longitude, servingRadiusInKms)) {
              Restaurant r = new Restaurant();
              r.setRestaurantId(restaurant.getRestaurantId());
              r.setName(restaurant.getName());
              r.setCity(restaurant.getCity());
              r.setImageUrl(restaurant.getImageUrl());
              r.setLatitude(restaurant.getLatitude());
              r.setLongitude(restaurant.getLongitude());
              r.setOpensAt(restaurant.getOpensAt());
              r.setClosesAt(restaurant.getClosesAt());
              r.setAttributes(restaurant.getAttributes());
              restaurantList.add(r);    
            }
          }
          return CompletableFuture.completedFuture(restaurantList);
        }
        

  }

