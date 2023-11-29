/* eslint-disable check-file/filename-blocklist */
// temporary disable eslint rule for this file during form cleanup
import { act, renderHook } from "@testing-library/react";
import React from "react";

import { mockCatalogDiff } from "test-utils/mock-data/mockCatalogDiff";
import { mockConnection } from "test-utils/mock-data/mockConnection";
import {
  mockDestinationDefinition,
  mockDestinationDefinitionSpecification,
  mockDestinationDefinitionVersion,
} from "test-utils/mock-data/mockDestination";
import {
  mockSourceDefinition,
  mockSourceDefinitionSpecification,
  mockSourceDefinitionVersion,
} from "test-utils/mock-data/mockSource";
import { mockWorkspace } from "test-utils/mock-data/mockWorkspace";
import { TestWrapper } from "test-utils/testutils";

import {
  WebBackendConnectionRead,
  WebBackendConnectionRequestBody,
  WebBackendConnectionUpdate,
} from "core/api/types/AirbyteClient";

import { ConnectionEditHookFormServiceProvider } from "./ConnectionEditHookFormService";
import { useConnectionEditService } from "./ConnectionEditService";
import { useConnectionFormService } from "../ConnectionForm/ConnectionFormService";

jest.mock("core/api", () => ({
  useCurrentWorkspace: () => mockWorkspace,
  useGetConnection: () => mockConnection,
  useSourceDefinition: () => mockSourceDefinition,
  useDestinationDefinition: () => mockDestinationDefinition,
  useGetConnectionQuery:
    () =>
    async ({ withRefreshedCatalog }: WebBackendConnectionRequestBody) =>
      withRefreshedCatalog ? utils.getMockConnectionWithRefreshedCatalog() : mockConnection,
  useUpdateConnection: () => ({
    mutateAsync: jest.fn(async (connection: WebBackendConnectionUpdate) => {
      const { sourceCatalogId, ...connectionUpdate } = connection;
      return { ...mockConnection, ...connectionUpdate, catalogId: sourceCatalogId ?? mockConnection.catalogId };
    }),
    isLoading: false,
  }),
  useSourceDefinitionVersion: () => mockSourceDefinitionVersion,
  useDestinationDefinitionVersion: () => mockDestinationDefinitionVersion,
  useGetSourceDefinitionSpecification: () => mockSourceDefinitionSpecification,
  useGetDestinationDefinitionSpecification: () => mockDestinationDefinitionSpecification,
}));

const utils = {
  getMockConnectionWithRefreshedCatalog: (): WebBackendConnectionRead => ({
    ...mockConnection,
    catalogDiff: mockCatalogDiff,
    catalogId: `${mockConnection.catalogId}1`,
  }),
};

describe("ConnectionEditHookFormServiceProvider", () => {
  const Wrapper: React.FC<React.PropsWithChildren> = ({ children }) => (
    <TestWrapper>
      {/* TODO: will be renamed to ConnectionEditServiceProvider*/}
      <ConnectionEditHookFormServiceProvider connectionId={mockConnection.connectionId}>
        {children}
      </ConnectionEditHookFormServiceProvider>
    </TestWrapper>
  );

  const refreshSchema = jest.fn();

  beforeEach(() => {
    refreshSchema.mockReset();
  });

  it("should load a Connection from a connectionId", async () => {
    const { result } = renderHook(useConnectionEditService, {
      wrapper: Wrapper,
    });

    expect(result.current.connection).toEqual(mockConnection);
  });

  it("should update a connection and set the current connection object to the updated connection", async () => {
    const { result } = renderHook(useConnectionEditService, {
      wrapper: Wrapper,
    });

    const mockUpdateConnection: WebBackendConnectionUpdate = {
      connectionId: mockConnection.connectionId,
      name: "new connection name",
      prefix: "new connection prefix",
      syncCatalog: { streams: [] },
    };

    await act(async () => {
      await result.current.updateConnection(mockUpdateConnection);
    });

    expect(result.current.connection).toEqual({ ...mockConnection, ...mockUpdateConnection });
  });

  it("should refresh schema", async () => {
    // Need to combine the hooks so both can be used.
    const useMyTestHook = () => {
      return [useConnectionEditService(), useConnectionFormService()] as const;
    };

    const { result } = renderHook(useMyTestHook, {
      wrapper: Wrapper,
    });

    const mockUpdateConnection: WebBackendConnectionUpdate = {
      connectionId: mockConnection.connectionId,
      name: "new connection name",
      prefix: "new connection prefix",
      syncCatalog: { streams: [] },
    };

    await act(async () => {
      await result.current[0].updateConnection(mockUpdateConnection);
    });

    expect(result.current[0].connection).toEqual({ ...mockConnection, ...mockUpdateConnection });

    await act(async () => {
      await result.current[1].refreshSchema();
    });

    expect(result.current[0].schemaHasBeenRefreshed).toBe(true);
    expect(result.current[0].schemaRefreshing).toBe(false);
    expect(result.current[0].connection).toEqual(utils.getMockConnectionWithRefreshedCatalog());
  });

  it("should refresh schema only if the sync catalog has diffs", async () => {
    // Need to combine the hooks so both can be used.
    const useMyTestHook = () =>
      ({ editService: useConnectionEditService(), formService: useConnectionFormService() }) as const;

    const { result } = renderHook(useMyTestHook, {
      wrapper: Wrapper,
    });

    const connectionUpdate = {
      connectionId: mockConnection.connectionId,
      name: "new connection name",
      prefix: "new connection prefix",
    };

    const updatedConnection: WebBackendConnectionRead = {
      ...mockConnection,
      ...connectionUpdate,
    };

    jest.spyOn(utils, "getMockConnectionWithRefreshedCatalog").mockImplementationOnce(
      (): WebBackendConnectionRead => ({
        ...updatedConnection,
        catalogDiff: { transforms: [] },
      })
    );

    await act(async () => {
      await result.current.editService.updateConnection(connectionUpdate);
      await result.current.formService.refreshSchema();
    });

    expect(result.current.editService.schemaHasBeenRefreshed).toBe(false);
    expect(result.current.editService.schemaRefreshing).toBe(false);
    expect(result.current.editService.connection).toEqual(updatedConnection);
  });

  it("should discard the refreshed schema", async () => {
    const useMyTestHook = () =>
      ({ editService: useConnectionEditService(), formService: useConnectionFormService() }) as const;

    const { result } = renderHook(useMyTestHook, {
      wrapper: Wrapper,
    });

    const connectionUpdate: WebBackendConnectionUpdate = {
      connectionId: mockConnection.connectionId,
      name: "new connection name",
      prefix: "new connection prefix",
    };

    const updatedConnection = { ...mockConnection, ...connectionUpdate };

    await act(async () => {
      await result.current.formService.refreshSchema();
      await result.current.editService.updateConnection(connectionUpdate);
      result.current.editService.discardRefreshedSchema();
    });

    expect(result.current.editService.schemaHasBeenRefreshed).toBe(false);
    expect(result.current.editService.schemaRefreshing).toBe(false);
    expect(result.current.editService.connection).toEqual(updatedConnection);
  });
});
