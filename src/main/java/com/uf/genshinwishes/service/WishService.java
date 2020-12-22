package com.uf.genshinwishes.service;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.uf.genshinwishes.dto.mapper.WishMapper;
import com.uf.genshinwishes.dto.mihoyo.MihoyoUserDTO;
import com.uf.genshinwishes.exception.ApiError;
import com.uf.genshinwishes.exception.ErrorType;
import com.uf.genshinwishes.model.BannerType;
import com.uf.genshinwishes.model.User;
import com.uf.genshinwishes.model.Wish;
import com.uf.genshinwishes.repository.WishRepository;
import com.uf.genshinwishes.service.mihoyo.MihoyoImRestClient;
import com.uf.genshinwishes.service.mihoyo.MihoyoRestClient;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor(onConstructor = @__(@Autowired))
public class WishService {

    private WishRepository wishRepository;
    private MihoyoRestClient mihoyoRestClient;
    private MihoyoImRestClient mihoyoImRestClient;
    private WishMapper wishMapper;

    @Transactional
    public Map<BannerType, Integer> importWishes(User user, String authkey) {
        if (Strings.isNullOrEmpty(user.getMihoyoUid())) {
            throw new ApiError(ErrorType.NO_MIHOYO_LINKED);
        }

        MihoyoUserDTO mihoyoUser = mihoyoImRestClient.getUserInfo(authkey);

        if (!user.getMihoyoUid().equals(mihoyoUser.getUser_id()))
            throw new ApiError(ErrorType.MIHOYO_UID_DIFFERENT);

        Optional<Date> ifLastWishDate = wishRepository.findFirstByUserOrderByTimeDescIdDesc(user).map(Wish::getTime);

        Map<BannerType, Integer> counts = Maps.newHashMap();

        Arrays.stream(BannerType.values()).forEach(type -> {
            // Attach user to wishes
            List<Wish> wishes = paginateWishesOlderThanDate(authkey, type, ifLastWishDate).stream().map(wish -> {
                wish.setUser(user);

                return wish;
            }).collect(Collectors.toList());

            counts.put(type, wishes.size());

            // Most recent = highest ID
            Collections.reverse(wishes);

            wishRepository.saveAll(wishes);
        });

        return counts;
    }

    public Map<BannerType, Collection<Wish>> getBanners(User user) {
        Multimap<BannerType, Wish> wishesByBanner = MultimapBuilder.hashKeys(BannerType.values().length).arrayListValues().build();
        ;

        Arrays.stream(BannerType.values())
            .forEach(type -> wishesByBanner.putAll(type, wishRepository.findFirst100ByUserAndGachaTypeOrderByIdDesc(user, type.getType())));

        return wishesByBanner.asMap();
    }

    @Transactional
    public void deleteAll(User user) {
        wishRepository.deleteByUser(user);
    }

    private List<Wish> paginateWishesOlderThanDate(String authkey, BannerType bannerType, Optional<Date> ifLastWishDate) {
        List<Wish> wishes = Lists.newLinkedList();
        List<Wish> pageWishes;
        Integer currentPage = 1;

        while (!(pageWishes = getWishesForPage(authkey, bannerType, currentPage++)).isEmpty()) {
            // We got a wish that's older than the last import
            if (ifLastWishDate.isPresent()) {
                if (pageWishes.get(pageWishes.size() - 1).getTime().before(ifLastWishDate.get())
                    || pageWishes.get(pageWishes.size() - 1).getTime().equals(ifLastWishDate.get())) {
                    wishes.addAll(pageWishes.stream()
                        .filter(wish -> wish.getTime().after(ifLastWishDate.get()))
                        .collect(Collectors.toList()));

                    break;
                }
            }

            wishes.addAll(pageWishes);
        }

        return wishes;
    }

    private List<Wish> getWishesForPage(String authkey, BannerType bannerType, Integer page) {
        return mihoyoRestClient.getWishes(authkey, bannerType, page)
            .stream()
            .map(wishMapper::fromMihoyo)
            .collect(Collectors.toList());
    }

    public List<Wish> findByUserAndBannerType(User user, BannerType bannerType, Integer page) {
        return this.wishRepository.findAllByUserAndGachaTypeOrderByIdDesc(
            PageRequest.of(page, 10),
            user,
            bannerType.getType())
            .getContent();
    }

    public Integer countAllByUserAndGachaType(User user, BannerType bannerType) {
        return this.wishRepository.countAllByUserAndGachaType(user, bannerType.getType());
    }

    public Map<BannerType, Integer> countAllByUser(User user) {
        return Arrays.stream(BannerType.values())
            .collect(Collectors.toMap(
                (banner) -> banner,
                (banner) -> this.wishRepository.countByUserAndGachaType(user, banner.getType())
            ));
    }
}
