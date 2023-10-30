import { renderHook } from "@testing-library/react";

import { mockConnection } from "test-utils/mock-data/mockConnection";
import {
  mockDestinationDefinitionSpecification,
  mockDestinationDefinitionVersion,
} from "test-utils/mock-data/mockDestination";
import { mockWorkspace } from "test-utils/mock-data/mockWorkspace";

import { useInitialHookFormValues } from "./hookFormConfig";

jest.mock("core/api", () => ({
  useCurrentWorkspace: () => mockWorkspace,
}));

describe("#useInitialHookFormValues", () => {
  it("should generate initial values w/ no 'not create' mode", () => {
    const { result } = renderHook(() =>
      useInitialHookFormValues(mockConnection, mockDestinationDefinitionVersion, mockDestinationDefinitionSpecification)
    );
    expect(result.current).toMatchSnapshot();
    expect(result.current.name).toBeDefined();
  });

  it("should generate initial values w/ 'not create' mode: false", () => {
    const { result } = renderHook(() =>
      useInitialHookFormValues(
        mockConnection,
        mockDestinationDefinitionVersion,
        mockDestinationDefinitionSpecification,
        false
      )
    );
    expect(result.current).toMatchSnapshot();
    expect(result.current.name).toBeDefined();
  });

  it("should generate initial values w/ 'not create' mode: true", () => {
    const { result } = renderHook(() =>
      useInitialHookFormValues(
        mockConnection,
        mockDestinationDefinitionVersion,
        mockDestinationDefinitionSpecification,
        true
      )
    );
    expect(result.current).toMatchSnapshot();
    expect(result.current.name).toBeUndefined();
  });

  // This is a low-priority test
  it.todo(
    "should test for supportsDbt+initialValues.transformations and supportsNormalization+initialValues.normalization"
  );
});
