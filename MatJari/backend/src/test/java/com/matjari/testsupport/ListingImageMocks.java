package com.matjari.testsupport;

import com.matjari.repository.ListingImageIndex;
import com.matjari.service.ListingImageService;
import org.mockito.Mockito;

/** 사진 참조(V33) 조회가 항상 빈 결과인 {@link ListingImageService} 목. 기존 서비스 테스트가 사진과 무관할 때 쓴다. */
public final class ListingImageMocks {

    private ListingImageMocks() {}

    public static ListingImageService noImages() {
        return Mockito.mock(ListingImageService.class, invocation ->
                invocation.getMethod().getReturnType() == ListingImageIndex.class ? ListingImageIndex.EMPTY : null);
    }
}
