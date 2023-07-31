
/*
 *
 *  * Copyright (c) Crio.Do 2019. All rights reserved
 *
 */

package com.crio.qeats.services;

import com.crio.qeats.dto.Restaurant;
import com.crio.qeats.exchanges.GetRestaurantsRequest;
import com.crio.qeats.exchanges.GetRestaurantsResponse;
import com.crio.qeats.repositoryservices.RestaurantRepositoryService;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Log4j2
public class RestaurantServiceImpl implements RestaurantService {

  private final Double peakHoursServingRadiusInKms = 3.0;
  private final Double normalHoursServingRadiusInKms = 5.0;
  @Autowired
  private RestaurantRepositoryService restaurantRepositoryService;


  // TODO: CRIO_TASK_MODULE_RESTAURANTSAPI - Implement findAllRestaurantsCloseby.
  // Check RestaurantService.java file for the interface contract.
  @Override
  public GetRestaurantsResponse findAllRestaurantsCloseBy(
      GetRestaurantsRequest getRestaurantsRequest, LocalTime currentTime) {

    List<Restaurant> restaurants;

    int timing = currentTime.getHour() * 100 + currentTime.getMinute();
    Double latitude = getRestaurantsRequest.getLatitude();
    Double longitude = getRestaurantsRequest.getLongitude();

    if ((timing >= 800 && timing <= 1000) || (timing >= 1300 && timing <= 1400) || 
      (timing >= 1900 && timing <= 2100)) {
      restaurants = restaurantRepositoryService.findAllRestaurantsCloseBy(
      getRestaurantsRequest.getLatitude(), getRestaurantsRequest.getLongitude(), 
      currentTime, peakHoursServingRadiusInKms);
    } else {
      restaurants = restaurantRepositoryService.findAllRestaurantsCloseBy(
      getRestaurantsRequest.getLatitude(), getRestaurantsRequest.getLongitude(), 
      currentTime, normalHoursServingRadiusInKms);
    }
    GetRestaurantsResponse response = new GetRestaurantsResponse(restaurants);
    return response;

  }


  // TODO: CRIO_TASK_MODULE_RESTAURANTSEARCH
  // Implement findRestaurantsBySearchQuery. The request object has the search string.
  // We have to combine results from multiple sources:
  // 1. Restaurants by name (exact and inexact)
  // 2. Restaurants by cuisines (also called attributes)
  // 3. Restaurants by food items it serves
  // 4. Restaurants by food item attributes (spicy, sweet, etc)
  // Remember, a restaurant must be present only once in the resulting list.
  // Check RestaurantService.java file for the interface contract.
  @Override
  public GetRestaurantsResponse findRestaurantsBySearchQuery(
      GetRestaurantsRequest getRestaurantsRequest, LocalTime currentTime) {

    GetRestaurantsResponse response = new GetRestaurantsResponse();

    Double latitude = getRestaurantsRequest.getLatitude();
    Double longitude = getRestaurantsRequest.getLongitude();
    String searchFor = getRestaurantsRequest.getSearchFor();

    // Added code:

    int timing = currentTime.getHour() * 100 + currentTime.getMinute();
    Double currentRadius=0.0;

    if ((timing >= 800 && timing <= 1000) || (timing >= 1300 && timing <= 1400) || 
      (timing >= 1900 && timing <= 2100)) {
        currentRadius = peakHoursServingRadiusInKms;
    } else {
        currentRadius = normalHoursServingRadiusInKms;
    }

    //-----
    
    if (searchFor.equals("")) {
      List<Restaurant> restaurantList = new ArrayList<Restaurant>() {};
      response.setRestaurants(restaurantList);
      return response;
    } else {
      List<Restaurant> restaurantList = restaurantRepositoryService.findRestaurantsByName(
          latitude, longitude, searchFor, currentTime, currentRadius);
      restaurantList.addAll(restaurantRepositoryService.findRestaurantsByAttributes(
          latitude, longitude, searchFor, currentTime, currentRadius));
      restaurantList.addAll(restaurantRepositoryService.findRestaurantsByItemName(
          latitude, longitude, searchFor, currentTime, currentRadius));
      restaurantList.addAll(restaurantRepositoryService.findRestaurantsByItemAttributes(
          latitude, longitude, searchFor, currentTime, currentRadius));
      List<Restaurant> responseList = restaurantList.stream().distinct()
          .collect(Collectors.toList());
      response.setRestaurants(responseList);
    }
    return response;
  }

  // TODO: CRIO_TASK_MODULE_MULTITHREADING
  // Implement multi-threaded version of RestaurantSearch.
  // Implement variant of findRestaurantsBySearchQuery which is at least 1.5x time faster than
  // findRestaurantsBySearchQuery.
  @Override
  public GetRestaurantsResponse findRestaurantsBySearchQueryMt(
      GetRestaurantsRequest getRestaurantsRequest, LocalTime currentTime) {

     return null;
  }
}

