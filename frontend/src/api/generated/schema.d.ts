/**
 * OpenAPI 타입 생성 전 사용하는 최소 계약입니다.
 * packages/api-contract/openapi.yaml이 확장되면 생성 결과로 교체합니다.
 */
export interface paths {
  '/system/health': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': {
              status: 'UP';
              service: 'betchu-backend';
            };
          };
        };
      };
    };
  };
}
